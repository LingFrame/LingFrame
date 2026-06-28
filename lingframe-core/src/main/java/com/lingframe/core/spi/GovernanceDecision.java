package com.lingframe.core.spi;

import com.lingframe.api.security.AccessType;
import lombok.Builder;
import lombok.Data;

import java.time.Duration;

/**
 * 治理决策结果 (Runtime Object)
 * 承载仲裁后的最终判定。
 * <p>
 * 微内核解耦：从 governance 扩展包移至 spi 包，
 * 因为 {@link GovernancePolicyProvider} 的返回类型属于内核契约。
 */
@Data
@Builder
public class GovernanceDecision {
    private String source; // 决策来源 (e.g. "Patch", "Annotation", "Inference")
    private String requiredPermission;
    private AccessType accessType;
    private Boolean auditEnabled;
    private String auditAction;
    private Duration timeout;
    private Integer rateLimitPerSecond;
    private Integer maxConcurrentThreads;
    private Integer retryCount;
    private String fallbackValue;
    private Integer cpuBudgetMsPerMinute;
    private Integer memoryBudgetMb;

    // 快速构建空对象
    public static GovernanceDecision empty() {
        return GovernanceDecision.builder().build();
    }

    /**
     * 判断当前决策是否真的携带了治理指令。
     * 调用治理参数与权限/审计一样，都属于有效决策的一部分。
     */
    public boolean hasAnyDirective() {
        return requiredPermission != null
                || accessType != null
                || auditEnabled != null
                || auditAction != null
                || timeout != null
                || rateLimitPerSecond != null
                || maxConcurrentThreads != null
                || retryCount != null
                || fallbackValue != null
                || cpuBudgetMsPerMinute != null
                || memoryBudgetMb != null;
    }
}
