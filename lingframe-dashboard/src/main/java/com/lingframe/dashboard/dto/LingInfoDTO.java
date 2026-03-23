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
    private String status; // 全局聚合状态: ACTIVE, INACTIVE, DEGRADED, STOPPING, REMOVED
    private List<VersionInfo> versionDetails; // 所有运行版本的明细树
    private ResourcePermissions permissions;
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
    }
}