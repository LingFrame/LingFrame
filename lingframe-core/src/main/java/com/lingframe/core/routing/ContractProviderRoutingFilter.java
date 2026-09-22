package com.lingframe.core.routing;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingCoreRoutableTarget;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.pipeline.FilterPhase;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.model.EngineTrace;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import com.lingframe.core.spi.RoutableTarget;
import com.lingframe.core.spi.TrafficRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * L0 provider 级路由过滤器。
 * <p>
 * 在 Pipeline 最早阶段（{@link FilterPhase#PROVIDER_ROUTING}）执行，
 * 从裸 contractId FQSID 中按 provider 权重选择目标 lingId 并设置 {@code ctx.runtime}，
 * 让后续过滤器直接使用已解析的 runtime。
 * <p>
 * 契约级路由与灵元级路由共存：契约级 FQSID（裸 contractId）由本过滤器处理，
 * 旧格式 FQSID（{@code lingId:serviceName}）不触发此阶段，直接放行让下游按 lingId 路由。
 * <p>
 * 方法级资格过滤：候选 provider 中，只有真正声明了被调用方法的 provider 才进入选择池。
 * 未声明该方法的 provider（如新灵元只实现了部分方法）被自然剔除，
 * 调用未覆盖方法时流量 100% 落到声明了该方法的 provider（如灵核 baseline）。
 * <p>
 * 路由层不引用实现方身份（灵核/灵元），只认 weight 和方法资格。
 * 身份在注册时沉淀为 weight 数值：灵核默认 weight=100，灵元默认 weight=0。
 * <p>
 * 迭代期版本区分：当同一灵元部署两个版本时，Provider 注册标识显式升级为
 * {@code lingId:version}（例如 {@code user-ling:1.0.0} 与 {@code user-ling:1.1.0}）。
 * 本过滤器通过 {@link ProviderDescriptor#providerKey()} 解析候选，
 * 支持迭代期二元版本路由。
 */
public class ContractProviderRoutingFilter implements LingInvocationFilter {

    private final LingServiceRegistry lingServiceRegistry;
    private final LingRepository lingRepository;
    private final ProviderWeightRouter providerWeightRouter;
    /** 灵元级路由器（负责旧格式 FQSID 的灵元级路由） */
    private final InstanceRoutingFilter instanceRoutingFilter;

    public ContractProviderRoutingFilter(LingServiceRegistry lingServiceRegistry,
            LingRepository lingRepository, ProviderWeightRouter providerWeightRouter) {
        this(lingServiceRegistry, lingRepository, providerWeightRouter, null);
    }

    public ContractProviderRoutingFilter(LingServiceRegistry lingServiceRegistry,
            LingRepository lingRepository, ProviderWeightRouter providerWeightRouter,
            TrafficRouter trafficRouter) {
        this.lingServiceRegistry = lingServiceRegistry;
        this.lingRepository = lingRepository;
        this.providerWeightRouter = providerWeightRouter;
        this.instanceRoutingFilter = new InstanceRoutingFilter(trafficRouter);
    }

    @Override
    public int getOrder() {
        return FilterPhase.PROVIDER_ROUTING;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        String fqsid = ctx.getServiceFQSID();
        if (fqsid == null || fqsid.isEmpty()) {
            return chain.doFilter(ctx);
        }
        InstanceRoutingFilter.validateScope(ctx);
        applyLingVersionPolicy(ctx);
        if (ctx.routing().getTargetInstance() != null) {
            LingInstance target = ctx.routing().getTargetInstance();
            InstanceRoutingFilter.validateTarget(ctx, target);
            // 裸契约预解析入口也需要补齐归属，供后续状态守卫与治理使用。
            ctx.setTargetLingId(target.getLingId());
            ctx.setTargetVersion(target.getVersion());
            resolveRuntime(ctx, target.getLingId());
            ctx.routing().setPreResolved(true);
            return chain.doFilter(ctx);
        }

        // 旧格式 FQSID（lingId:serviceName）走灵元级路由接管分支
        // 入口放行条件用 targetInstance（与原版 CanaryRoutingFilter 一致）：
        // 只锁 lingId 不锁 instance 时仍需本过滤器解析具体实例
        if (fqsid.indexOf(':') >= 0) {
            return routeByLingId(ctx, chain, fqsid);
        }

        // 识别新格式裸 contractId（无 lingId: 前缀）
        // 入口放行条件用 targetLingId（与原版 ContractProviderRoutingFilter 一致）：
        // 调用方已锁定灵元时本过滤器不覆盖入口意图
        if (ctx.getTargetLingId() != null) {
            RoutableTarget target = resolveRuntime(ctx, ctx.getTargetLingId());
            if (target == null && ctx.getTargetVersion() != null) {
                throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.ROUTE_FAILURE);
            }
            return chain.doFilter(ctx);
        }

        String contractId = fqsid;

        if (lingServiceRegistry == null) {
            // 无 serviceRegistry（native/test 场景），无法做契约级路由，放行
            return chain.doFilter(ctx);
        }
        List<ProviderDescriptor> providers = lingServiceRegistry.getProvidersByContractId(contractId);
        if (providers == null || providers.isEmpty()) {
            if (ctx.getTargetVersion() != null) {
                throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.ROUTE_FAILURE);
            }
            // 容错：契约未注册到 provider 索引，放行让后续过滤器处理
            return chain.doFilter(ctx);
        }

        // 方法级资格过滤：只保留真正声明了被调用方法的 provider
        List<ProviderDescriptor> qualified = filterByMethod(providers, contractId, ctx);
        // 过滤后为空时 fallback 到全集（兼容灵元方法注册不全但权重仍需生效的场景）
        List<ProviderDescriptor> candidates = !qualified.isEmpty() ? qualified : providers;
        candidates = filterUnavailableProviders(candidates);
        if (candidates.isEmpty()) {
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.ROUTE_FAILURE,
                    "No provider instance is eligible to receive requests");
        }
        if (ctx.getTargetVersion() != null) {
            List<ProviderDescriptor> versionCandidates = new ArrayList<>();
            // 显式锁版本时不得因方法资格为空退回全集，更不能覆盖为另一版本。
            for (ProviderDescriptor candidate : qualified) {
                if (Objects.equals(ctx.getTargetVersion(), candidate.getVersion())) {
                    versionCandidates.add(candidate);
                }
            }
            candidates = versionCandidates;
            if (candidates.isEmpty()) {
                throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.ROUTE_FAILURE);
            }
        }

        // 标签优先匹配：若 ctx 带有请求标签（如 tenant/role/env），优先精确匹配候选 Provider
        ProviderDescriptor selected = selectByLabels(candidates, ctx);
        if (selected == null) {
            // 无标签命中时，退化到基于 Effective Weight 的 N 元加权概率分流
            selected = providerWeightRouter.selectProvider(candidates, ctx);
        }

        if (selected == null) {
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.ROUTE_FAILURE);
        }

        ctx.setTargetLingId(selected.getLingId());
        if (selected.getVersion() != null) {
            // 迭代期：锁版本路由
            ctx.setTargetVersion(selected.getVersion());
        }

        RoutableTarget target = lingRepository.getRoutableTarget(selected.getLingId());
        if (target == null) {
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.ROUTE_FAILURE);
        }
        ctx.setRuntime(target);

        return chain.doFilter(ctx);
    }

    /**
     * 仅显式限定灵元的入口消费局部策略。服务名必须直接命中注册契约，不猜测别名。
     * 同一次调用固定策略快照，后续实例路由只在已选版本内选择具体代次。
     */
    private void applyLingVersionPolicy(InvocationContext ctx) {
        if (providerWeightRouter == null) {
            return;
        }
        LingInstance pinned = ctx.routing().getTargetInstance();
        String lingId = ctx.getEffectiveLingId();
        if (lingId == null && pinned != null) {
            lingId = pinned.getLingId();
        }
        if (lingId == null) {
            return;
        }
        String fqsid = ctx.getServiceFQSID();
        int separator = fqsid.indexOf(':');
        String contractId = separator >= 0 ? fqsid.substring(separator + 1) : fqsid;
        // 旧的不完整入口保持原有失败路径，不能凭不完整名称绑定策略。
        if (contractId.isEmpty() || contractId.indexOf(':') >= 0) {
            return;
        }
        LingRoutingScope scope = new LingRoutingScope(lingId, contractId);
        LingVersionPolicy policy = providerWeightRouter.getLingVersionPolicy(scope);
        if (!policy.isConfigured()) {
            return;
        }
        ctx.routing().setLingVersionPolicy(policy);
        RoutableTarget runtime = resolveRuntime(ctx, lingId);
        if (runtime == null || lingServiceRegistry == null) {
            throw scopedRouteFailure(ctx);
        }
        List<LingInstance> ready = new ArrayList<>(runtime.getReadyInstances());
        ready.removeIf(LingInstance::isAdmissionDisabled);
        ready.removeIf(instance -> !InstanceRoutingFilter.hasScopedMethod(ctx, instance));
        if (pinned != null) {
            InstanceRoutingFilter.validateTarget(ctx, pinned);
            if (!ready.contains(pinned)) {
                throw scopedRouteFailure(ctx);
            }
        }
        String requestedVersion = ctx.getTargetVersion();
        if (requestedVersion == null && pinned != null) {
            requestedVersion = pinned.getVersion();
        }
        List<ProviderDescriptor> providers = lingServiceRegistry.getProvidersByContractId(contractId);
        List<ProviderDescriptor> candidates = new ArrayList<>();
        if (providers != null) {
            for (ProviderDescriptor provider : filterByMethod(providers, contractId, ctx)) {
                String version = provider.getVersion();
                if (!lingId.equals(provider.getLingId()) || version == null
                        || !policy.getVersionWeights().containsKey(version)
                        || (requestedVersion != null && !requestedVersion.equals(version))) {
                    continue;
                }
                for (LingInstance instance : ready) {
                    if (lingId.equals(instance.getLingId()) && version.equals(instance.getVersion())) {
                        candidates.add(provider);
                        break;
                    }
                }
            }
        }
        ProviderDescriptor selected = null;
        String reason = "explicit-version";
        if (requestedVersion != null && !candidates.isEmpty()) {
            selected = candidates.get(0);
        } else if (requestedVersion == null) {
            reason = "labels";
            selected = selectScopedByLabels(candidates, ready, ctx);
            if (selected == null) {
                reason = "weights";
                selected = selectScopedByWeight(candidates, policy);
            }
        }
        if (selected == null) {
            throw scopedRouteFailure(ctx);
        }
        ctx.setTargetLingId(lingId);
        ctx.setTargetVersion(selected.getVersion());
        ctx.routing().setPolicyRevision(policy.getRevision());
        ctx.routing().setRoutingReason(reason);
        ctx.execution().addTrace(EngineTrace.builder().source("ContractProviderRoutingFilter")
                .action("Scoped version policy " + policy.getRevision() + " selected "
                        + selected.getVersion() + " by " + reason).type("OK").depth(0).build());
    }

    private ProviderDescriptor selectScopedByLabels(List<ProviderDescriptor> candidates,
            List<LingInstance> ready, InvocationContext ctx) {
        if (ctx.getLabels() == null || ctx.getLabels().isEmpty()) {
            return null;
        }
        for (ProviderDescriptor candidate : candidates) {
            for (LingInstance instance : ready) {
                if (candidate.getLingId().equals(instance.getLingId())
                        && candidate.getVersion().equals(instance.getVersion())
                        && matchLabels(instance.getLabels(), ctx.getLabels())) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private ProviderDescriptor selectScopedByWeight(List<ProviderDescriptor> candidates, LingVersionPolicy policy) {
        long total = 0;
        for (ProviderDescriptor candidate : candidates) {
            total += policy.getVersionWeights().get(candidate.getVersion());
        }
        if (total == 0) {
            return null;
        }
        long choice = ThreadLocalRandom.current().nextLong(total);
        for (ProviderDescriptor candidate : candidates) {
            choice -= policy.getVersionWeights().get(candidate.getVersion());
            if (choice < 0) {
                return candidate;
            }
        }
        return null;
    }

    private LingInvocationException scopedRouteFailure(InvocationContext ctx) {
        return new LingInvocationException(ctx.getServiceFQSID(), LingInvocationException.ErrorKind.ROUTE_FAILURE,
                "No eligible version in scoped routing policy");
    }

    /**
     * 标签优先匹配：当 InvocationContext 携带标签时，尝试在候选 Provider 中寻找完全兼容匹配的 Provider。
     * <p>
     * 版本对齐：命中实例的 version 必须与描述符的 version 一致；描述符 version 为 null（迁移期灵元）
     * 时放宽到任意实例版本。否则迭代期同 lingId 多版本并存场景下，标签命中可能误选旧版本实例，
     * 使下游版本锁定失效。
     */
    private ProviderDescriptor selectByLabels(List<ProviderDescriptor> candidates, InvocationContext ctx) {
        if (ctx == null || ctx.getLabels() == null || ctx.getLabels().isEmpty()) {
            return null;
        }
        Map<String, String> reqLabels = ctx.getLabels();
        for (ProviderDescriptor desc : candidates) {
            RoutableTarget rt = lingRepository != null ? lingRepository.getRoutableTarget(desc.getLingId()) : null;
            if (rt instanceof LingRuntime) {
                LingRuntime runtime = (LingRuntime) rt;
                if (runtime.getInstancePool() != null) {
                    for (LingInstance instance : runtime.getInstancePool().getActiveInstances()) {
                        if (instance.isAdmissionDisabled() || !versionMatches(desc, instance)) {
                            continue;
                        }
                        if (matchLabels(instance.getLabels(), reqLabels)) {
                            return desc;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 描述符版本与实例版本是否对齐。
     * <p>
     * 描述符 version 为 null（无版本概念，仅灵核 provider）时放宽到任意实例版本；
     * 否则要求严格相等，避免多版本并存时误选旧版本。
     */
    private boolean versionMatches(ProviderDescriptor desc, LingInstance instance) {
        if (desc.getVersion() == null) {
            return true;
        }
        return Objects.equals(desc.getVersion(), instance.getVersion());
    }

    private boolean matchLabels(Map<String, String> instLabels, Map<String, String> reqLabels) {
        if (instLabels == null) {
            return false;
        }
        for (Map.Entry<String, String> entry : reqLabels.entrySet()) {
            String val = instLabels.get(entry.getKey());
            if (!Objects.equals(val, entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 方法级资格过滤：遍历候选 provider，只保留声明了被调用方法的 provider。
     * <p>
     * 判定依据：{@link LingServiceRegistry#hasMethod(String, String, String[])}，
     * 查询键为 {@code lingId:contractId}（FQSID 格式，与注册时一致）。
     * <p>
     * 未声明该方法的 provider 被剔除——这是「新灵元只实现部分方法，未覆盖方法自动落回灵核」
     * 的核心机制。
     */
    private List<ProviderDescriptor> filterByMethod(List<ProviderDescriptor> providers, String contractId,
            InvocationContext ctx) {
        String methodName = ctx.getMethodName();
        String[] paramTypes = ctx.getParameterTypeNames();
        if (methodName == null || paramTypes == null) {
            // 入口未提供方法签名，无法做方法级过滤，返回全集让权重路由决策
            return providers;
        }
        List<ProviderDescriptor> qualified = new ArrayList<>();
        for (ProviderDescriptor desc : providers) {
            String fqsid = desc.getLingId() + ":" + contractId;
            if (lingServiceRegistry.hasMethod(fqsid, methodName, paramTypes)) {
                qualified.add(desc);
            }
        }
        return qualified;
    }

    /**
     * 全局 provider 路由只允许存在明确无可接流实例的 provider 被剔除。
     * <p>
     * 测试或 native 路径可能只提供抽象 RoutableTarget 而不暴露成员列表，
     * 此时保留描述符并交由后续运行时解析，避免把未知状态误判为不可用。
     */
    private List<ProviderDescriptor> filterUnavailableProviders(List<ProviderDescriptor> providers) {
        if (lingRepository == null) {
            return providers;
        }
        List<ProviderDescriptor> eligible = new ArrayList<>();
        for (ProviderDescriptor provider : providers) {
            RoutableTarget target = lingRepository.getRoutableTarget(provider.getLingId());
            if (target == null) {
                continue;
            }
            if (!(target instanceof LingRuntime) && !(target instanceof LingCoreRoutableTarget)) {
                eligible.add(provider);
                continue;
            }
            List<LingInstance> ready = target.getReadyInstances();
            if (ready == null) {
                eligible.add(provider);
                continue;
            }
            boolean canReceive = false;
            for (LingInstance instance : ready) {
                if (instance != null && !instance.isAdmissionDisabled()
                        && versionMatches(provider, instance)) {
                    canReceive = true;
                    break;
                }
            }
            if (canReceive) {
                eligible.add(provider);
            }
        }
        return eligible;
    }

    /**
     * 旧格式 FQSID（{@code lingId:serviceName}）灵元级路由。
     * <p>
     * 从 FQSID 提取 lingId，用 {@link TrafficRouter} 在 READY 实例中选目标实例并写入 ctx，
     * 让后续过滤器直接使用已解析的 runtime 与 targetInstance。
     *
     * @param ctx   调用上下文
     * @param chain 过滤器链
     * @param fqsid 旧格式 FQSID（lingId:serviceName）
     * @return chain.doFilter 的结果
     * @throws LingInvocationException 路由失败（runtime/实例缺失）
     */
    private Object routeByLingId(InvocationContext ctx, LingFilterChain chain, String fqsid) throws Throwable {
        String lingId = extractLingId(fqsid);
        RoutableTarget runtime = resolveRuntime(ctx, lingId);
        if (runtime == null) {
            if (ctx.getTargetVersion() == null && ctx.execution().getMode().isGovernOnly()) {
                // GOVERN_ONLY 允许灵核入口只借道治理，不强依赖真实灵元路由结果
                return chain.doFilter(ctx);
            }
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.ROUTE_FAILURE);
        }

        // 灵核 RoutableTarget（非 LingRuntime）：设置 runtime 后放行，交 InstanceRoutingFilter.routeCoreTarget
        // 从灵核单例实例池取实例。灵元 delegate 到灵核（@LingReference(lingId="lingcore-app")）是 NORMAL 模式
        // 合法调用，不应在此拦截；GOVERN_ONLY/SIMULATION 借道治理同样放行。
        if (!(runtime instanceof LingRuntime)) {
            ctx.setRuntime(runtime);
            return chain.doFilter(ctx);
        }

        ctx.setRuntime(runtime);
        if (ctx.execution().getMode().isSimulation() && ctx.getTargetVersion() == null
                && runtime.getReadyInstances().isEmpty()) {
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.ROUTE_FAILURE);
        }
        // 旧格式仍局限于指定灵元，复用同一个实例选路实现，避免版本约束分叉。
        return instanceRoutingFilter.doFilter(ctx, chain);
    }

    private RoutableTarget resolveRuntime(InvocationContext ctx, String lingId) {
        RoutableTarget runtime = ctx.getRuntime();
        if (runtime != null) {
            return runtime;
        }
        RoutableTarget resolved = lingRepository != null ? lingRepository.getRoutableTarget(lingId) : null;
        if (resolved != null) {
            ctx.setRuntime(resolved);
        }
        return resolved;
    }

    private String extractLingId(String fqsid) {
        int separator = fqsid.indexOf(':');
        return separator > 0 ? fqsid.substring(0, separator) : fqsid;
    }
}
