package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginInfoDTO {

    private String pluginId;
    private String status;              // ACTIVE, LOADED, UNLOADED, STARTING, STOPPING
    private List<String> versions;      // 所有已部署版本
    private String activeVersion;       // 当前激活版本
    private Integer canaryPercent;      // 灰度比例 0-100
    private String canaryVersion;       // 灰度版本
    private ResourcePermissions permissions;
    private long installedAt;           // 安装时间戳
    private Map<String, Object> metadata;

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
    }
}