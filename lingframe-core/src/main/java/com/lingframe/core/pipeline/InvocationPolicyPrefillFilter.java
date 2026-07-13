package com.lingframe.core.pipeline;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;

/**
 * 治理意图预填充过滤器。
 * <p>
 * 在 RESILIENCE 阶段之前，把灵元级 effective policy（静态策略 + 动态补丁合并）
 * 的 invocation 字段预填到 ctx.governance()，让弹性治理组件通过 ctx 读取治理意图，
 * 守护"ctx 为 pipeline 唯一通行证"的原则。
 * <p>
 * 背景：{@link ResilienceGovernanceFilter} 在 {@code RESILIENCE=300} 阶段执行，
 * 早于 {@code GOVERNANCE=500}，此时 {@code ctx.governance()} 的字段尚未被
 * {@link GovernanceDecisionFilter} 填充。若不做预填充，弹性组件只能绕过 ctx 直读
 * {@code LingRuntimeConfig} 底座或 {@link LocalGovernanceRegistry}，前者会引入
 * "治理下发双写"裂缝，后者会打破"ctx 为唯一通行证"原则。本过滤器在两者之前把
 * 灵元级 invocation 字段填入 ctx，让弹性组件的 ctx 读取真正生效。
 * <p>
 * 仅填充灵元级 invocation 字段（timeout/rateLimit/maxConcurrent），不涉及方法级决策
 * （permissions/audits/references）——后者由 {@link GovernanceDecisionFilter} 在
 * GOVERNANCE 阶段基于方法解析结果填充，可覆盖预填充值（方法级 &gt; 灵元级，合理）。
 * <p>
 * 性能注意：合并采用 {@link GovernancePolicy.InvocationPolicy#copy()} + applyPatch
 * （7 字段浅拷贝），不调用 {@link GovernancePolicy#merge}（会深拷贝 permissions/
 * audits/references 四个列表，热路径 GC 开销不可接受）。
 */
public class InvocationPolicyPrefillFilter implements LingInvocationFilter {

    private final LingRepository lingRepository;
    private final LocalGovernanceRegistry governanceRegistry;

    public InvocationPolicyPrefillFilter(LingRepository lingRepository,
                                         LocalGovernanceRegistry governanceRegistry) {
        this.lingRepository = lingRepository;
        this.governanceRegistry = governanceRegistry;
    }

    @Override
    public int getOrder() {
        return FilterPhase.POLICY_PREFILL;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        String lingId = ctx.getEffectiveLingId();
        GovernancePolicy.InvocationPolicy effective = resolveEffectiveInvocationPolicy(lingId);
        if (effective != null) {
            InvocationGovernanceState state = ctx.governance();
            // 只填弹性组件真正消费的三项；null 字段不覆盖，保留 ctx 现有值
            if (effective.getTimeoutMs() != null) {
                state.setTimeoutMs(effective.getTimeoutMs());
            }
            if (effective.getRateLimitPerSecond() != null) {
                state.setRateLimitPerSecond(effective.getRateLimitPerSecond());
            }
            if (effective.getMaxConcurrentThreads() != null) {
                state.setMaxConcurrentThreads(effective.getMaxConcurrentThreads());
            }
        }
        return chain.doFilter(ctx);
    }

    /**
     * 解析灵元生效的调用治理策略（InvocationPolicy 级合并，不深拷贝列表）。
     * <p>
     * 优先级：patch 非 null 字段覆盖静态策略字段（与 {@code GovernancePolicy.merge} 语义一致，
     * 但只作用于 InvocationPolicy，避免触碰 permissions/audits/references 列表）。
     */
    private GovernancePolicy.InvocationPolicy resolveEffectiveInvocationPolicy(String lingId) {
        if (lingId == null || lingRepository == null) {
            return null;
        }
        GovernancePolicy.InvocationPolicy staticInv = readStaticInvocation(lingId);
        GovernancePolicy.InvocationPolicy patchInv = readPatchInvocation(lingId);
        if (staticInv == null && patchInv == null) {
            return null;
        }
        if (patchInv == null) {
            return staticInv;
        }
        if (staticInv == null) {
            return patchInv;
        }
        // 字段级合并：patch 非 null 字段覆盖 static
        GovernancePolicy.InvocationPolicy merged = staticInv.copy();
        merged.applyPatch(patchInv);
        return merged;
    }

    /**
     * 读取静态策略中的 invocation（来自 ling.yml 声明的 GovernancePolicy）。
     */
    private GovernancePolicy.InvocationPolicy readStaticInvocation(String lingId) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            return null;
        }
        LingInstance instance = runtime.getInstancePool().getDefault();
        if (instance == null) {
            return null;
        }
        LingDefinition definition = instance.getDefinition();
        if (definition == null) {
            return null;
        }
        GovernancePolicy governance = definition.getGovernance();
        return governance != null ? governance.getInvocation() : null;
    }

    /**
     * 读取动态补丁中的 invocation（来自 LocalGovernanceRegistry 的 patch）。
     */
    private GovernancePolicy.InvocationPolicy readPatchInvocation(String lingId) {
        if (governanceRegistry == null) {
            return null;
        }
        GovernancePolicy patch = governanceRegistry.getPatch(lingId);
        return patch != null ? patch.getInvocation() : null;
    }
}
