package com.lingframe.dashboard.converter;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionInfo;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.dashboard.dto.LingInfoDTO;
import com.lingframe.dashboard.dto.TrafficStatsDTO;
import com.lingframe.core.router.CanaryRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 灵元运行时信息转换为 DTO
 */
public class LingInfoConverter {

    public LingInfoDTO toDTO(LingRuntime runtime,
            CanaryRouter canaryRouter,
            PermissionService permissionService,
            GovernancePolicy policy) {
        String lingId = runtime.getLingId();
        List<LingInstance> activeInstances = runtime.getInstancePool().getActiveInstances();
        List<LingInstance> allInstances = runtime.getInstancePool().getAllInstances();
        int canaryPercent = canaryRouter.getCanaryPercent(lingId);

        List<LingInfoDTO.VersionInfo> versionDetails = allInstances.stream().map(instance -> {
            boolean isCurCanary = isCanary(instance);
            boolean isCurDefault = instance == runtime.getInstancePool().getDefault();
            int weight = 0;
            if (activeInstances.contains(instance)) {
                if (isCurCanary) {
                    weight = canaryPercent;
                } else if (isCurDefault) {
                    weight = 100 - canaryPercent;
                }
            }
            return LingInfoDTO.VersionInfo.builder()
                    .version(instance.getVersion())
                    .status(instance.getStateMachine().current().name())
                    .isDefault(isCurDefault)
                    .isCanary(isCurCanary)
                    .trafficWeight(weight)
                    .build();
        }).collect(Collectors.toList());

        return LingInfoDTO.builder()
                .lingId(lingId)
                .status(runtime.getStateMachine().current().name())
                .versionDetails(versionDetails)
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
                .activeRequests(runtime.getActiveRequests().get())
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
        List<String> ipcServices = new ArrayList<>();
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

    private boolean isCanary(LingInstance instance) {
        if (instance == null || instance.getDefinition() == null) {
            return false;
        }
        if (instance.getDefinition().getProperties() == null) {
            return false;
        }
        Object value = instance.getDefinition().getProperties().get("canary");
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }
}
