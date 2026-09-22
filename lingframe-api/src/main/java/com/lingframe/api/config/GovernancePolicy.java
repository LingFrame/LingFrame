package com.lingframe.api.config;

import lombok.*;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 治理策略子节点
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernancePolicy implements Serializable {

    // 给字段赋默认值，防止 Builder 覆盖导致为 null (需要 @Builder.Default)
    @Builder.Default
    private List<PermissionRule> permissions = new ArrayList<>();

    @Builder.Default
    private List<CapabilityRule> capabilities = new ArrayList<>();

    @Builder.Default
    private List<AuditRule> audits = new ArrayList<>();

    /**
     * 跨灵元服务引用的治理规则。
     * <p>
     * 与 permissions/audits 平级，用于「被调方方法名」维度的治理配置——
     * 即原本散落在 {@code @LingReference.timeout}/{@code fallback} 注解上的语义。
     * 治理入参从注解收敛到 YAML，实现注解只声明契约。
     */
    @Builder.Default
    private List<ReferenceRule> references = new ArrayList<>();

    /**
     * 调用治理配置。
     * 与 capabilities / permissions / audits 分区，避免把调用控制语义继续塞进资源权限列表。
     */
    @Builder.Default
    private InvocationPolicy invocation = new InvocationPolicy();

    public GovernancePolicy copy() {
        GovernancePolicy copy = new GovernancePolicy();

        if (this.permissions != null) {
            copy.permissions = this.permissions.stream()
                    .map(PermissionRule::copy)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        if (this.capabilities != null) {
            copy.capabilities = this.capabilities.stream()
                    .map(CapabilityRule::copy)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        if (this.audits != null) {
            copy.audits = this.audits.stream()
                    .map(AuditRule::copy)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        if (this.references != null) {
            copy.references = this.references.stream()
                    .map(ReferenceRule::copy)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        if (this.invocation != null) {
            copy.invocation = this.invocation.copy();
        }

        return copy;
    }

    /**
     * 将补丁策略合并到当前策略。
     * <p>
     * 规则：
     * 1. permissions / capabilities / audits 采用“非空列表覆盖”；
     * 2. invocation 采用字段级非 null 覆盖。
     * </p>
     */
    public void applyPatch(GovernancePolicy patch) {
        if (patch == null) {
            return;
        }

        if (patch.permissions != null && !patch.permissions.isEmpty()) {
            this.permissions = patch.permissions.stream()
                    .map(PermissionRule::copy)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        if (patch.capabilities != null && !patch.capabilities.isEmpty()) {
            this.capabilities = patch.capabilities.stream()
                    .map(CapabilityRule::copy)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        if (patch.audits != null && !patch.audits.isEmpty()) {
            this.audits = patch.audits.stream()
                    .map(AuditRule::copy)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        if (patch.references != null && !patch.references.isEmpty()) {
            this.references = patch.references.stream()
                    .map(ReferenceRule::copy)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        if (patch.invocation != null) {
            if (this.invocation == null) {
                this.invocation = new InvocationPolicy();
            }
            this.invocation.applyPatch(patch.invocation);
        }
    }

    public static GovernancePolicy merge(GovernancePolicy base, GovernancePolicy patch) {
        GovernancePolicy merged = base == null ? new GovernancePolicy() : base.copy();
        merged.applyPatch(patch);
        return merged;
    }

    /**
     * 资源能力申请规则
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CapabilityRule implements Serializable {
        private String capability;
        private String accessType;

        public CapabilityRule copy() {
            CapabilityRule copy = new CapabilityRule();
            copy.capability = this.capability;
            copy.accessType = this.accessType;
            return copy;
        }
    }

    /**
     * ACL 权限控制规则
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PermissionRule implements Serializable {
        private String methodPattern;
        private String permissionId;

        public PermissionRule copy() {
            PermissionRule copy = new PermissionRule();
            copy.methodPattern = this.methodPattern;
            copy.permissionId = this.permissionId;
            return copy;
        }
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditRule implements Serializable {
        private String methodPattern;
        private String action;
        @Builder.Default
        private boolean enabled = true;

        public AuditRule copy() {
            AuditRule copy = new AuditRule();
            copy.methodPattern = this.methodPattern;
            copy.action = this.action;
            copy.enabled = this.enabled;
            return copy;
        }
    }

    /**
     * 跨灵元服务引用的治理规则。
     * <p>
     * 把原本散在 {@code @LingReference.timeout}/{@code fallback} 注解上的治理入参
     * 收敛到 YAML 配置。注解只声明契约（路由锚点），治理入参归 YAML。
     * <ul>
     *   <li>{@code referencePattern}：被调方方法名匹配键，支持精确方法名或 {@code prefix*} 模糊匹配。
     *       命中时由 {@code StandardGovernancePolicyProvider} 在 P2 阶段覆到 GovernanceDecision</li>
     *   <li>{@code timeoutMs}：调用超时（毫秒），null 表示不动</li>
     *   <li>{@code fallbackValue}：框架级异常时的静默降级值（字符串），null 表示不动</li>
     *   <li>{@code retryCount}：重试次数，null 表示不动</li>
     * </ul>
     * 命中时由 {@code StandardGovernancePolicyProvider} 在 P2 阶段覆到 GovernanceDecision。
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReferenceRule implements Serializable {
        private String referencePattern;
        private Integer timeoutMs;
        private String fallbackValue;
        private Integer retryCount;

        public ReferenceRule copy() {
            ReferenceRule copy = new ReferenceRule();
            copy.referencePattern = this.referencePattern;
            copy.timeoutMs = this.timeoutMs;
            copy.fallbackValue = this.fallbackValue;
            copy.retryCount = this.retryCount;
            return copy;
        }
    }

    /**
     * 调用治理规则。
     * 第一阶段只收敛当前已有真实消费方的 3 个字段，先把热调闭环建立起来。
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvocationPolicy implements Serializable {
        private Integer timeoutMs;
        private Integer rateLimitPerSecond;
        private Integer maxConcurrentThreads;
        private Integer retryCount;
        private String fallbackValue;
        private Integer cpuBudgetMsPerMinute;
        private Integer memoryBudgetMb;

        public InvocationPolicy copy() {
            InvocationPolicy copy = new InvocationPolicy();
            copy.timeoutMs = this.timeoutMs;
            copy.rateLimitPerSecond = this.rateLimitPerSecond;
            copy.maxConcurrentThreads = this.maxConcurrentThreads;
            copy.retryCount = this.retryCount;
            copy.fallbackValue = this.fallbackValue;
            copy.cpuBudgetMsPerMinute = this.cpuBudgetMsPerMinute;
            copy.memoryBudgetMb = this.memoryBudgetMb;
            return copy;
        }

        public void applyPatch(InvocationPolicy patch) {
            if (patch == null) {
                return;
            }
            if (patch.timeoutMs != null) {
                this.timeoutMs = patch.timeoutMs;
            }
            if (patch.rateLimitPerSecond != null) {
                this.rateLimitPerSecond = patch.rateLimitPerSecond;
            }
            if (patch.maxConcurrentThreads != null) {
                this.maxConcurrentThreads = patch.maxConcurrentThreads;
            }
            if (patch.retryCount != null) {
                this.retryCount = patch.retryCount;
            }
            if (patch.fallbackValue != null) {
                this.fallbackValue = patch.fallbackValue;
            }
            if (patch.cpuBudgetMsPerMinute != null) {
                this.cpuBudgetMsPerMinute = patch.cpuBudgetMsPerMinute;
            }
            if (patch.memoryBudgetMb != null) {
                this.memoryBudgetMb = patch.memoryBudgetMb;
            }
        }
    }

}
