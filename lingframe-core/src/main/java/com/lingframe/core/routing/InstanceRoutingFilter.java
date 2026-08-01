package com.lingframe.core.routing;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.pipeline.FilterPhase;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import com.lingframe.core.spi.RoutableTarget;
import com.lingframe.core.spi.TrafficRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * L1 实例级路由过滤器。
 * <p>
 * 在 {@link FilterPhase#ROUTING} 阶段执行，承接 L0 provider 路由
 * （{@link ContractProviderRoutingFilter}）已设置的 {@code ctx.runtime}，
 * 从选定灵元的 READY 实例池中选出具体 {@link LingInstance} 并回填到 ctx。
 * <p>
 * 补全原设计预留的实例路由层：provider 路由（选哪个实现方）与 instance 路由（选哪个实例）
 * 分阶段独立，策略各自可插拔。本过滤器只做 instance 维度决策，不重叠 provider 策略。
 * <p>
 * 触发条件：{@code ctx.runtime} 已设为 {@link LingRuntime} 且 {@code targetInstance} 未设。
 * 已由入口预解析（web 入口 {@code preResolveLingTarget}）或旧格式 FQSID
 * （{@code routeByLingId}）选定实例时，本过滤器短路放行，零重复执行。
 * <p>
 * 灵核路径：{@link InvocationContext#getLingRuntime()} 对灵核返回 null。灵核作为 CORE provider
 * 被 {@code @LingReference} 调用时为 NORMAL 模式（{@code SmartServiceProxy} 不显式设 mode，默认 NORMAL），
 * 本过滤器从灵核单例实例池取实例设为 targetInstance，让下游 {@code ContextIsolationFilter} 切灵核
 * ClassLoader、{@code TerminalInvokerFilter} 取灵核 Bean 真实执行；SIMULATION/GOVERN_ONLY 借道治理放行。
 * <p>
 * 版本锁定：迭代期 {@code ctx.targetVersion} 由 L0 阶段设置，本过滤器据此过滤候选实例，
 * 避免误选旧版本破坏灰度语义；迁移期 {@code targetVersion} 为 null 时不过滤。
 * <p>
 * 候选空处理：SIMULATION/GOVERN_ONLY 放行（借道治理，不要求真实目标）；
 * NORMAL 模式抛 {@code ROUTE_FAILURE}，与 {@code routeByLingId} 对称。
 *
 * @author lingframe
 */
public class InstanceRoutingFilter implements LingInvocationFilter {

    /** 灵元级流量路由策略（接管 instance 维度决策），无则兜底默认实例 */
    private final TrafficRouter trafficRouter;

    public InstanceRoutingFilter(TrafficRouter trafficRouter) {
        this.trafficRouter = trafficRouter;
    }

    @Override
    public int getOrder() {
        return FilterPhase.ROUTING;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        // 1. 已选实例（入口预解析 / 旧格式 FQSID / preResolved）→ 短路放行，零重复执行
        if (ctx.routing().getTargetInstance() != null) {
            return chain.doFilter(ctx);
        }

        // 2. 灵核路径（runtime 非 LingRuntime）或 runtime 未设 → 交 routeCoreTarget 处理
        LingRuntime lingRuntime = ctx.getLingRuntime();
        if (lingRuntime == null) {
            return routeCoreTarget(ctx, chain);
        }

        // 3. 从 READY 实例池选候选，按迭代期版本锁定过滤
        List<LingInstance> candidates = lingRuntime.getReadyInstances();
        String targetVersion = ctx.getTargetVersion();
        if (targetVersion != null) {
            // 迭代期锁版本：只保留 version 严格相等的实例，避免误选旧版本破坏灰度语义
            List<LingInstance> filtered = new ArrayList<>(candidates.size());
            for (LingInstance inst : candidates) {
                if (Objects.equals(targetVersion, inst.getVersion())) {
                    filtered.add(inst);
                }
            }
            candidates = filtered;
        }

        // 4. 候选空：SIMULATION/GOVERN_ONLY 放行（借道治理），NORMAL 抛 ROUTE_FAILURE
        if (candidates.isEmpty()) {
            if (ctx.execution().getMode().isSimulation()
                    || ctx.execution().getMode().isGovernOnly()) {
                return chain.doFilter(ctx);
            }
            throw new LingInvocationException(ctx.getServiceFQSID(),
                    LingInvocationException.ErrorKind.ROUTE_FAILURE);
        }

        // 5. 选实例：TrafficRouter 优先，无则兜底默认实例（与 routeByLingId 对称）
        LingInstance target;
        if (trafficRouter != null) {
            target = trafficRouter.route(candidates, ctx);
            if (target == null) {
                throw new LingInvocationException(ctx.getServiceFQSID(),
                        LingInvocationException.ErrorKind.ROUTE_FAILURE);
            }
        } else {
            // 无 TrafficRouter（native/test 场景）：兜底选默认实例，再退到候选首位
            LingInstance defaultInst = lingRuntime.getInstancePool().getDefault();
            // 默认实例必须在版本过滤后的候选池里：迭代期 getDefault() 通常返回稳定版（旧版本），
            // 若不在候选池则放弃，退到候选首位，避免绕过版本锁定破坏灰度语义
            target = (defaultInst != null && candidates.contains(defaultInst))
                    ? defaultInst
                    : candidates.get(0);
        }

        // 6. 回填实例路由结果（与 routeByLingId 对称：set targetInstance + targetLingId + targetVersion）
        ctx.routing().setTargetInstance(target);
        ctx.setTargetLingId(target.getLingId());
        ctx.setTargetVersion(target.getVersion());
        return chain.doFilter(ctx);
    }

    /**
     * 灵核路径或 runtime 未设时的路由处理。
     * <p>
     * runtime 未设：放行（SIMULATION/GOVERN_ONLY 借道治理，交下游 ContextIsolationFilter 处理）。
     * <p>
     * 灵核作为 CORE provider 被 {@code @LingReference} 调用时为 NORMAL 模式
     * （{@code SmartServiceProxy} 不显式设 mode，默认 NORMAL）：
     * 从灵核单例实例池取实例设为 targetInstance，让 {@code ContextIsolationFilter}
     * 切灵核 ClassLoader、{@code TerminalInvokerFilter} 取灵核 Bean 真实执行。
     * SIMULATION/GOVERN_ONLY 借道治理不要求真实目标实例，放行。
     *
     * @param ctx   调用上下文
     * @param chain 过滤器链
     * @return chain.doFilter 的结果
     * @throws LingInvocationException NORMAL 模式下灵核实例池为空时抛 ROUTE_FAILURE
     */
    private Object routeCoreTarget(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        RoutableTarget runtime = ctx.getRuntime();
        if (runtime == null) {
            // runtime 未设 → 放行（交下游 ContextIsolationFilter 处理）
            return chain.doFilter(ctx);
        }
        // SIMULATION/GOVERN_ONLY 借道治理不要求真实目标实例，放行
        if (ctx.execution().getMode().isSimulation()
                || ctx.execution().getMode().isGovernOnly()) {
            return chain.doFilter(ctx);
        }
        // NORMAL 模式：从灵核单例实例池取实例设为 targetInstance
        List<LingInstance> coreInstances = runtime.getReadyInstances();
        if (coreInstances == null || coreInstances.isEmpty()) {
            throw new LingInvocationException(ctx.getServiceFQSID(),
                    LingInvocationException.ErrorKind.ROUTE_FAILURE);
        }
        LingInstance coreTarget = coreInstances.get(0);
        ctx.routing().setTargetInstance(coreTarget);
        ctx.setTargetLingId(coreTarget.getLingId());
        // 灵核无版本概念，targetVersion 保持原值（通常为 null，不覆盖）
        return chain.doFilter(ctx);
    }
}
