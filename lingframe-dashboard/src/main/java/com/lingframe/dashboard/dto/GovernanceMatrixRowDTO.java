package com.lingframe.dashboard.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 治理规则矩阵行 DTO。
 * 用于治理规则总览，按灵元+版本维度聚合治理配置，便于发现配置漂移。
 */
@Data
@Builder
public class GovernanceMatrixRowDTO {
    private String lingId;
    private String version;
    private boolean isDefault;
    /** 流量权重（0-100） */
    private int trafficWeight;
    // 调用治理参数
    private Integer timeoutMs;
    private Integer rateLimitPerSecond;
    private Integer maxConcurrentThreads;
    private Integer retryCount;
    private Integer cpuBudgetMsPerMinute;
    private Integer memoryBudgetMb;
    // 资源权限
    private Boolean dbRead;
    private Boolean dbWrite;
    private Boolean cacheRead;
    private Boolean cacheWrite;
}
