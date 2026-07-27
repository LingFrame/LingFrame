package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LingInfoDTO {

    private String lingId;
    /**
     * 灵元声明的首个契约 ID（兜底）；迁移阶段管理用此字段把 lingId 解析为真实 contractId,
     * 替代旧前端把 lingId 当 contractId 误用的 literal 'default' 兜底。
     * 多契约场景后续扩展。
     */
    private String contractId;
    private String status; // 全局聚合状态: ACTIVE, INACTIVE, DEGRADED, STOPPING, REMOVED
    private List<VersionInfo> versionDetails; // 所有运行版本的明细树
    private ResourcePermissions permissions;
    private InvocationGovernance invocationGovernance;
    private long installedAt; // 安装时间戳
    private Map<String, Object> metadata;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VersionInfo {
        private String version;
        private String status;
        private Boolean isDefault;
        private Boolean isCanary;
        private int trafficWeight; // 这个版本承载的流量占比 0-100
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResourcePermissions {
        @Builder.Default
        private boolean dbRead = true;
        @Builder.Default
        private boolean dbWrite = true;
        @Builder.Default
        private boolean cacheRead = true;
        @Builder.Default
        private boolean cacheWrite = true;
        @Builder.Default
        private boolean networkAccess = true;
        @Builder.Default
        private boolean fileAccess = false;
        @Builder.Default
        private List<String> ipcServices = new ArrayList<>();
        @Builder.Default
        private List<String> sqlCapabilities = new ArrayList<>();
        @Builder.Default
        private List<String> redisCapabilities = new ArrayList<>();
        @Builder.Default
        private List<String> extraCapabilities = new ArrayList<>();
        private String localCacheNamespaceStrategy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvocationGovernance {
        private Integer timeoutMs;
        private Integer rateLimitPerSecond;
        private Integer maxConcurrentThreads;
        private Integer retryCount;
        private String fallbackValue;
        private Integer cpuBudgetMsPerMinute;
        private Integer memoryBudgetMb;
    }
}
