package com.lingframe.core.governance;

import com.lingframe.api.security.AccessType;
import lombok.Builder;
import lombok.Data;

import java.time.Duration;

/**
 * 治理决策结果 (Runtime Object)
 * 承载仲裁后的最终判定
 */
@Data
@Builder
public class GovernanceDecision {
    private String requiredPermission;
    private AccessType accessType;
    private Boolean auditEnabled;
    private String auditAction;
    private Duration timeout;

    // 快速构建空对象
    public static GovernanceDecision empty() {
        return GovernanceDecision.builder().build();
    }
}