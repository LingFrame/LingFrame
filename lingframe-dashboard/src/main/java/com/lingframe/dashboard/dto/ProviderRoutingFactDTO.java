package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 运行时实际命中的 provider 事实聚合。
 * <p>
 * 事实由 Core 的 ProviderMetricsCollector 记录，包含实例代次、策略修订和选路原因，
 * 用于验证真实业务入口的灰度结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderRoutingFactDTO {

    private String contractId;
    private String lingId;
    private String version;
    private String instanceId;
    private String policyRevision;
    private String routingReason;
    private long totalInvocations;
    private long successCount;
    private long failureCount;
    private double actualPercent;
    private double avgDurationMs;
}
