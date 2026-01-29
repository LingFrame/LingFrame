package com.lingframe.dashboard.converter;

import com.lingframe.api.config.GovernancePolicy;
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
            PermissionService permissionService,
            GovernancePolicy policy) {
        String pluginId = runtime.getPluginId();

        return PluginInfoDTO.builder()
                .pluginId(pluginId)
                .status(runtime.getStatus().name())
                .versions(runtime.getAllVersions())
                .activeVersion(runtime.getVersion())
                .canaryPercent(canaryRouter.getCanaryPercent(pluginId))
                .canaryVersion(runtime.getCanaryVersion())
                .permissions(extractPermissions(pluginId, permissionService, policy))
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

    private PluginInfoDTO.ResourcePermissions extractPermissions(String pluginId, PermissionService permissionService,
            GovernancePolicy policy) {
        // 直接查询权限配置，不受开发模式影响
        var sqlPermission = permissionService.getPermission(pluginId, Capabilities.STORAGE_SQL);
        var cachePermission = permissionService.getPermission(pluginId, Capabilities.CACHE_LOCAL);

        // 根据 AccessType 判断读写权限
        boolean dbRead = sqlPermission != null && sqlPermission.satisfies(AccessType.READ);
        boolean dbWrite = sqlPermission != null && sqlPermission.satisfies(AccessType.WRITE);
        boolean cacheRead = cachePermission != null && cachePermission.satisfies(AccessType.READ);
        boolean cacheWrite = cachePermission != null && cachePermission.satisfies(AccessType.WRITE);

        // 提取 IPC 权限
        java.util.List<String> ipcServices = new java.util.ArrayList<>();
        if (policy != null && policy.getCapabilities() != null) {
            for (GovernancePolicy.CapabilityRule rule : policy.getCapabilities()) {
                if (rule.getCapability().startsWith("ipc:")) {
                    ipcServices.add(rule.getCapability().substring(4)); // 去掉 ipc: 前缀
                }
            }
        }

        return PluginInfoDTO.ResourcePermissions.builder()
                .dbRead(dbRead)
                .dbWrite(dbWrite)
                .cacheRead(cacheRead)
                .cacheWrite(cacheWrite)
                .ipcServices(ipcServices)
                .build();
    }
}