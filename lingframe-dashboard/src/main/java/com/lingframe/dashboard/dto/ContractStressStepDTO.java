package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 契约流量演练单步调用结果 DTO。
 * <p>
 * 承载单次真实微内核模拟演练调用的选路结果、真实耗时与链路追踪 ID。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractStressStepDTO {
    /** 契约 ID */
    private String contractId;
    /** 链路追踪 ID */
    private String traceId;
    /** 选中的提供方路由键（灵核为 lingcore-app，灵元为 lingId:version） */
    private String hitProviderKey;
    /** 选中的灵元 ID */
    private String lingId;
    /** 选中的版本号（灵核为 null） */
    private String version;
    /** 提供方类型：CORE / LING */
    private String type;
    /** 演练模式：DRY_RUN / PENETRATION */
    private String mode;
    /** 真实流水线调用耗时（毫秒） */
    private double durationMs;
    /** 调用时间戳 */
    private long timestamp;
}
