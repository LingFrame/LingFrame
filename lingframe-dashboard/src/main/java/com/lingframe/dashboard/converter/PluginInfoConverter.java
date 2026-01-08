package com.lingframe.dashboard.converter;


import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.governance.LocalGovernanceRegistry;
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
                               LocalGovernanceRegistry registry) {
        String pluginId = runtime.getPluginId();

        return PluginInfoDTO.builder()
                .pluginId(pluginId)
                .status(runtime.getStatus().name())
                .versions(runtime.getAllVersions())
                .activeVersion(runtime.getVersion())
                .canaryPercent(canaryRouter.getCanaryPercent(pluginId))
                .canaryVersion(runtime.getCanaryVersion())
                .permissions(extractPermissions(registry.getPatch(pluginId)))
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

    private PluginInfoDTO.ResourcePermissions extractPermissions(GovernancePolicy policy) {
        boolean dbRead = true, dbWrite = true, cacheRead = true, cacheWrite = true;

        if (policy != null && policy.getPermissions() != null) {
            for (GovernancePolicy.PermissionRule rule : policy.getPermissions()) {
                String perm = rule.getPermissionId();
                if (perm == null) continue;

                if (perm.contains(":deny") || perm.endsWith(":false")) {
                    if (perm.contains("db:read")) dbRead = false;
                    else if (perm.contains("db:write")) dbWrite = false;
                    else if (perm.contains("cache:read")) cacheRead = false;
                    else if (perm.contains("cache:write")) cacheWrite = false;
                }
            }
        }

        return PluginInfoDTO.ResourcePermissions.builder()
                .dbRead(dbRead)
                .dbWrite(dbWrite)
                .cacheRead(cacheRead)
                .cacheWrite(cacheWrite)
                .build();
    }
}