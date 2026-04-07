package com.lingframe.core.pipeline;

import com.lingframe.api.security.AccessType;
import lombok.Getter;
import lombok.Setter;

/**
 * 治理阶段协议分区。
 * 这里承载的是权限、审计、超时等“治理意图”，而不是路由事实或执行产物。
 */
@Getter
@Setter
public class InvocationGovernanceState {

    /**
     * 所需权限。
     */
    private String requiredPermission;

    /**
     * 访问类型。
     */
    private AccessType accessType;

    /**
     * 是否需要审计。
     */
    private boolean shouldAudit;

    /**
     * 审计动作名。
     */
    private String auditAction;

    /**
     * 规则来源，便于诊断“是谁做出的治理决定”。
     */
    private String ruleSource;

    /**
     * 治理阶段计算出的超时（毫秒）。
     * 后续线程隔离阶段只消费这里的结果，不再重新猜测。
     */
    private Integer timeoutMs;

    /**
     * 治理阶段计算出的限流阈值（QPS）。
     * 弹性治理阶段优先消费这里的最终值。
     */
    private Integer rateLimitPerSecond;

    /**
     * 治理阶段计算出的最大并发线程数。
     * 线程隔离阶段优先消费这里的最终值。
     */
    private Integer maxConcurrentThreads;

    /**
     * 治理阶段计算出的重试次数。
     * 终端执行阶段消费这里的结果，避免“声明了 retry 但从未真正生效”。
     */
    private Integer retryCount;

    /**
     * 重试耗尽后的回退值。
     * 先保持字符串形态，避免在治理层提前绑定目标方法返回类型。
     */
    private String fallbackValue;

    /**
     * 每分钟 CPU 预算（毫秒）。
     * 当前阶段只做观测与告警，不做硬拒绝。
     */
    private Integer cpuBudgetMsPerMinute;

    /**
     * 内存预算（MB）。
     * 当前阶段只做估算与告警，不做虚假的硬限制。
     */
    private Integer memoryBudgetMb;

    void reset() {
        this.requiredPermission = null;
        this.accessType = null;
        this.shouldAudit = false;
        this.auditAction = null;
        this.ruleSource = null;
        this.timeoutMs = null;
        this.rateLimitPerSecond = null;
        this.maxConcurrentThreads = null;
        this.retryCount = null;
        this.fallbackValue = null;
        this.cpuBudgetMsPerMinute = null;
        this.memoryBudgetMb = null;
    }

    void copyFrom(InvocationGovernanceState source) {
        if (source == null) {
            return;
        }
        this.requiredPermission = source.requiredPermission;
        this.accessType = source.accessType;
        this.shouldAudit = source.shouldAudit;
        this.auditAction = source.auditAction;
        this.ruleSource = source.ruleSource;
        this.timeoutMs = source.timeoutMs;
        this.rateLimitPerSecond = source.rateLimitPerSecond;
        this.maxConcurrentThreads = source.maxConcurrentThreads;
        this.retryCount = source.retryCount;
        this.fallbackValue = source.fallbackValue;
        this.cpuBudgetMsPerMinute = source.cpuBudgetMsPerMinute;
        this.memoryBudgetMb = source.memoryBudgetMb;
    }
}
