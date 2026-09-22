package com.lingframe.starter.governance;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.governance.GovernanceAdminService;
import com.lingframe.core.pipeline.InvocationContext;

/**
 * 入口调用治理参数下发器。
 * <p>
 * 解析灵元生效策略（静态 + 补丁合并）并把 timeout / rateLimit / maxConcurrentThreads
 * 下发到 {@link InvocationContext#governance()} 分区。
 * <p>
 * 策略解析委托 {@link GovernanceAdminService#getEffectivePolicy}，
 * 不再直接持有 {@code LocalGovernanceRegistry} 或重写 merge 同构代码——
 * 那是治理内核的正式职责，适配层只负责把内核已算出的策略下发到调用上下文。
 */
public class EntryInvocationGovernanceResolver {

    private final GovernanceAdminService governanceAdmin;

    public EntryInvocationGovernanceResolver(GovernanceAdminService governanceAdmin) {
        this.governanceAdmin = governanceAdmin;
    }

    public void applyTo(InvocationContext context, String lingId) {
        if (context == null || lingId == null || lingId.isEmpty()) {
            return;
        }

        GovernancePolicy effectivePolicy = governanceAdmin == null ? null : governanceAdmin.getEffectivePolicy(lingId);
        if (effectivePolicy == null || effectivePolicy.getInvocation() == null) {
            return;
        }

        GovernancePolicy.InvocationPolicy invocation = effectivePolicy.getInvocation();
        if (invocation.getTimeoutMs() != null) {
            context.governance().setTimeoutMs(invocation.getTimeoutMs());
        }
        if (invocation.getRateLimitPerSecond() != null) {
            context.governance().setRateLimitPerSecond(invocation.getRateLimitPerSecond());
        }
        if (invocation.getMaxConcurrentThreads() != null) {
            context.governance().setMaxConcurrentThreads(invocation.getMaxConcurrentThreads());
        }
    }
}
