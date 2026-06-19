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
import java.util.Objects;
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
        int canaryPercent = canaryRouter.getCanaryPercent(lingId);

        // 只展示活跃实例：dyingQueue 中的实例处于 STOPPING/DEAD 过渡态，
        // 对前端用户无意义且会造成 reload 时短暂出现"多版本"的困惑。
        // reload 场景下旧实例会先进入 dyingQueue 再被 tearDown，不应展示。
        List<LingInfoDTO.VersionInfo> versionDetails = activeInstances.stream()
                .filter(instance -> instance.getDefinition() != null)
                .map(instance -> {
            boolean isCurCanary = isCanary(instance);
            boolean isCurDefault = instance == runtime.getInstancePool().getDefault();
            int weight = 0;
            if (isCurCanary) {
                weight = canaryPercent;
            } else if (isCurDefault) {
                weight = 100 - canaryPercent;
            }
            return LingInfoDTO.VersionInfo.builder()
                    .version(instance.getVersion())
                    .status(instance.currentStatus().name())
                    .isDefault(isCurDefault)
                    .isCanary(isCurCanary)
                    .trafficWeight(weight)
                    .build();
        }).collect(Collectors.toList());

        return LingInfoDTO.builder()
                .lingId(lingId)
                .status(runtime.currentStatus().name())
                .versionDetails(versionDetails)
                .permissions(extractPermissions(lingId, permissionService, policy))
                .invocationGovernance(extractInvocationGovernance(runtime, policy))
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
        List<String> sqlCapabilities = new ArrayList<>();
        List<String> redisCapabilities = new ArrayList<>();
        List<String> extraCapabilities = new ArrayList<>();
        if (policy != null && policy.getCapabilities() != null) {
            for (GovernancePolicy.CapabilityRule rule : policy.getCapabilities()) {
                if (rule == null || rule.getCapability() == null) {
                    continue;
                }
                String capability = rule.getCapability();
                if (capability.startsWith("ipc:")) {
                    ipcServices.add(capability.substring(4)); // 去掉 ipc: 前缀
                } else if (capability.startsWith("storage:sql:table:")) {
                    sqlCapabilities.add(capability);
                } else if (capability.startsWith("cache:redis:")) {
                    redisCapabilities.add(capability);
                } else if (!Objects.equals(capability, Capabilities.STORAGE_SQL)
                        && !Objects.equals(capability, Capabilities.CACHE_LOCAL)
                        && !Objects.equals(capability, Capabilities.Ling_ENABLE)) {
                    extraCapabilities.add(capability);
                }
            }
        }

        return LingInfoDTO.ResourcePermissions.builder()
                .dbRead(dbRead)
                .dbWrite(dbWrite)
                .cacheRead(cacheRead)
                .cacheWrite(cacheWrite)
                .ipcServices(ipcServices)
                .sqlCapabilities(sqlCapabilities)
                .redisCapabilities(redisCapabilities)
                .extraCapabilities(extraCapabilities)
                .localCacheNamespaceStrategy("lingId + cacheName + rawKey")
                .build();
    }

    private LingInfoDTO.InvocationGovernance extractInvocationGovernance(LingRuntime runtime, GovernancePolicy policy) {
        GovernancePolicy.InvocationPolicy invocation = policy == null ? null : policy.getInvocation();
        Integer timeoutMs = invocation == null ? null : invocation.getTimeoutMs();
        Integer rateLimitPerSecond = invocation == null ? null : invocation.getRateLimitPerSecond();
        Integer maxConcurrentThreads = invocation == null ? null : invocation.getMaxConcurrentThreads();
        Integer retryCount = invocation == null ? null : invocation.getRetryCount();
        String fallbackValue = invocation == null ? null : invocation.getFallbackValue();
        Integer cpuBudgetMsPerMinute = invocation == null ? null : invocation.getCpuBudgetMsPerMinute();
        Integer memoryBudgetMb = invocation == null ? null : invocation.getMemoryBudgetMb();

        if (runtime != null && runtime.getConfig() != null) {
            if (timeoutMs == null) {
                timeoutMs = runtime.getConfig().getDefaultTimeoutMs();
            }
            if (rateLimitPerSecond == null) {
                rateLimitPerSecond = runtime.getConfig().getRateLimitPerSecond();
            }
            if (maxConcurrentThreads == null) {
                maxConcurrentThreads = runtime.getConfig().getBulkheadMaxConcurrent();
            }
        }

        return LingInfoDTO.InvocationGovernance.builder()
                .timeoutMs(timeoutMs)
                .rateLimitPerSecond(rateLimitPerSecond)
                .maxConcurrentThreads(maxConcurrentThreads)
                .retryCount(retryCount)
                .fallbackValue(fallbackValue)
                .cpuBudgetMsPerMinute(cpuBudgetMsPerMinute)
                .memoryBudgetMb(memoryBudgetMb)
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
