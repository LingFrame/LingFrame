package com.lingframe.dashboard.converter;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionInfo;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.dashboard.dto.LingInfoDTO;
import com.lingframe.dashboard.dto.TrafficStatsDTO;
import com.lingframe.dashboard.router.CanaryRouter;

/**
 * 单元运行时信息转换为 DTO
 */
public class LingInfoConverter {

    public LingInfoDTO toDTO(LingRuntime runtime,
            CanaryRouter canaryRouter,
            PermissionService permissionService,
            GovernancePolicy policy) {
        String lingId = runtime.getLingId();

        return LingInfoDTO.builder()
                .lingId(lingId)
                .status(runtime.getStatus().name())
                .versions(runtime.getAllVersions())
                .activeVersion(runtime.getVersion())
                .canaryPercent(canaryRouter.getCanaryPercent(lingId))
                .canaryVersion(runtime.getCanaryVersion())
                .permissions(extractPermissions(lingId, permissionService, policy))
                .installedAt(runtime.getInstalledAt())
                .build();
    }

    public TrafficStatsDTO toTrafficStats(LingRuntime runtime) {
        long total = runtime.getTotalRequests().get();
        long stable = runtime.getStableRequests().get();
        long canary = runtime.getCanaryRequests().get();

        return TrafficStatsDTO.builder()
                .lingId(runtime.getLingId())
                .totalRequests(total)
                .v1Requests(stable)
                .v2Requests(canary)
                .v1Percent(total > 0 ? (stable * 100.0 / total) : 0)
                .v2Percent(total > 0 ? (canary * 100.0 / total) : 0)
                .windowStartTime(runtime.getStatsWindowStart())
                .build();
    }

    private LingInfoDTO.ResourcePermissions extractPermissions(String lingId, PermissionService permissionService,
            GovernancePolicy policy) {
        // 直接查询权限配置，不受开发模式影响
        PermissionInfo sqlPermission = permissionService.getPermission(lingId, Capabilities.STORAGE_SQL);
        PermissionInfo cachePermission = permissionService.getPermission(lingId, Capabilities.CACHE_LOCAL);

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

        return LingInfoDTO.ResourcePermissions.builder()
                .dbRead(dbRead)
                .dbWrite(dbWrite)
                .cacheRead(cacheRead)
                .cacheWrite(cacheWrite)
                .ipcServices(ipcServices)
                .build();
    }
}