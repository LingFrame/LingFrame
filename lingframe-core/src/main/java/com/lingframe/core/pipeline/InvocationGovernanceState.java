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

    void reset() {
        this.requiredPermission = null;
        this.accessType = null;
        this.shouldAudit = false;
        this.auditAction = null;
        this.ruleSource = null;
        this.timeoutMs = null;
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
    }
}
