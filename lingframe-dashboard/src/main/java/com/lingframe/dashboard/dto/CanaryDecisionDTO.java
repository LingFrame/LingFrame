package com.lingframe.dashboard.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 金丝雀发布决策建议 DTO。
 * 基于稳定版与金丝雀版的健康指标对比，给出 ROLLBACK / FULL_RELEASE / OBSERVE 建议。
 */
@Data
@Builder
public class CanaryDecisionDTO {
    /** 决策建议：ROLLBACK / FULL_RELEASE / OBSERVE */
    private String recommendation;
    /** 决策理由（原始文本，向后兼容） */
    private String reason;
    /** 决策理由 i18n key（前端优先使用） */
    private String reasonKey;
    /** 稳定版错误率 */
    private double stableErrorRate;
    /** 金丝雀错误率 */
    private double canaryErrorRate;
    /** 稳定版 p99 延迟（ms） */
    private double stableP99;
    /** 金丝雀 p99 延迟（ms） */
    private double canaryP99;
    /** 数据样本是否充足 */
    private boolean sufficientData;
}
