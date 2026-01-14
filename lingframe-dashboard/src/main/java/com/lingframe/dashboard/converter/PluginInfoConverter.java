package com.lingframe.dashboard.converter;

import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.plugin.PluginRuntime;
import com.lingframe.dashboard.dto.PluginInfoDTO;
import com.lingframe.dashboard.dto.TrafficStatsDTO;
import com.lingframe.dashboard.router.CanaryRouter;

/**
 * 插件运行时信息转换为 DTO
 */
public class PluginInfoConverter {

    public PluginInfoDTO toDTO(PluginRuntime runtime,
            CanaryRouter canaryRouter,
            PermissionService permissionService) {
        String pluginId = runtime.getPluginId();

        return PluginInfoDTO.builder()
                .pluginId(pluginId)
                .status(runtime.getStatus().name())
                .versions(runtime.getAllVersions())
                .activeVersion(runtime.getVersion())
                .canaryPercent(canaryRouter.getCanaryPercent(pluginId))
                .canaryVersion(runtime.getCanaryVersion())
                .permissions(extractPermissions(pluginId, permissionService))
                .installedAt(runtime.getInstalledAt())
                .build();
    }

    public TrafficStatsDTO toTrafficStats(PluginRuntime runtime) {
        long total = runtime.getTotalRequests().get();
        long stable = runtime.getStableRequests().get();
        long canary = runtime.getCanaryRequests().get();

        return TrafficStatsDTO.builder()
                .pluginId(runtime.getPluginId())
                .totalRequests(total)
                .v1Requests(stable)
                .v2Requests(canary)
                .v1Percent(total > 0 ? (stable * 100.0 / total) : 0)
                .v2Percent(total > 0 ? (canary * 100.0 / total) : 0)
                .windowStartTime(runtime.getStatsWindowStart())
                .build();
    }

    private PluginInfoDTO.ResourcePermissions extractPermissions(String pluginId, PermissionService permissionService) {
        boolean dbRead = permissionService.isAllowed(pluginId, Capabilities.STORAGE_SQL, AccessType.READ);
        boolean dbWrite = permissionService.isAllowed(pluginId, Capabilities.STORAGE_SQL, AccessType.WRITE);
        boolean cacheRead = permissionService.isAllowed(pluginId, Capabilities.CACHE_LOCAL, AccessType.READ);
        boolean cacheWrite = permissionService.isAllowed(pluginId, Capabilities.CACHE_LOCAL, AccessType.WRITE);

        return PluginInfoDTO.ResourcePermissions.builder()
                .dbRead(dbRead)
                .dbWrite(dbWrite)
                .cacheRead(cacheRead)
                .cacheWrite(cacheWrite)
                .build();
    }
}