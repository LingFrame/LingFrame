package com.lingframe.dashboard.converter;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.constant.LingCoreConstants;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionInfo;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.metrics.LingHealthMetrics;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.metrics.ProviderMetricsCollector;
import com.lingframe.core.metrics.ProviderMetricsCollector.ProviderStats;
import com.lingframe.dashboard.dto.LingInfoDTO;
import com.lingframe.dashboard.dto.TrafficStatsDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 灵元运行时信息转换为 DTO
 * <p>
 * 迁移阶段与权重信息统一由治理存储层与 {@link com.lingframe.core.routing.ProviderWeightRouter}
 * 提供，本转换器不再依赖已删除的 {@code CanaryRouter}。
 */
public class LingInfoConverter {

    private final MetricsCollector metricsCollector;
    /** 灵元→契约 ID 解析器；nullable（native/test 场景），命中时填 LingInfoDTO.contractId */
    private final LingServiceRegistry lingServiceRegistry;
    /** 处约二维流量统计；nullable（native/test），命中时 toTrafficStats 读真实累计 */
    private final ProviderMetricsCollector providerMetricsCollector;

    public LingInfoConverter(MetricsCollector metricsCollector) {
        this(metricsCollector, null, null);
    }

    public LingInfoConverter(MetricsCollector metricsCollector, LingServiceRegistry lingServiceRegistry) {
        this(metricsCollector, lingServiceRegistry, null);
    }

    public LingInfoConverter(MetricsCollector metricsCollector,
            LingServiceRegistry lingServiceRegistry,
            ProviderMetricsCollector providerMetricsCollector) {
        this.metricsCollector = metricsCollector;
        this.lingServiceRegistry = lingServiceRegistry;
        this.providerMetricsCollector = providerMetricsCollector;
    }

    public LingInfoDTO toDTO(LingRuntime runtime,
            PermissionService permissionService,
            GovernancePolicy policy) {
        String lingId = runtime.getLingId();
        List<LingInstance> activeInstances = runtime.getInstancePool().getActiveInstances();

        // 只展示活跃实例：dyingQueue 中的实例处于 STOPPING/DEAD 过渡态，
        // 对前端用户无意义且会造成 reload 时短暂出现"多版本"的困惑。
        // reload 场景下旧实例会先进入 dyingQueue 再被 tearDown，不应展示。
        List<LingInfoDTO.VersionInfo> versionDetails = activeInstances.stream()
                .filter(instance -> instance.getDefinition() != null)
                .map(instance -> {
            boolean isCurDefault = instance == runtime.getInstancePool().getDefault();
            // canary 标志由 definition.properties.canary 携带（兼容数字 1 / boolean true / 字符串 "true"）
            boolean isCanary = extractCanaryFlag(instance.getDefinition());
            // 权重推断：canary 实例 30、default 实例 70；二元候选由 ProviderWeightRouter 在 Pipeline 内决策
            // 这里仅做展示推断，真实权重由治理存储层 + ProviderWeightRouter 下发覆盖
            int weight = isCanary ? 30 : (isCurDefault ? 70 : 0);
            return LingInfoDTO.VersionInfo.builder()
                    .version(instance.getVersion())
                    .status(instance.currentStatus().name())
                    .isDefault(isCurDefault)
                    .isCanary(isCanary)
                    .trafficWeight(weight)
                    .build();
        }).collect(Collectors.toList());

        return LingInfoDTO.builder()
                .lingId(lingId)
                .contractId(resolveContractId(lingId))
                .status(runtime.currentStatus().name())
                .versionDetails(versionDetails)
                .permissions(extractPermissions(lingId, permissionService, policy))
                .invocationGovernance(extractInvocationGovernance(runtime, policy))
                .installedAt(runtime.getInstalledAt())
                .build();
    }

    /**
     * 从 {@link LingDefinition#getProperties()} 提取 canary 标志。
     * <p>
     * 兼容三种携带形式：数字 1、boolean true、字符串 "true"/"1"。
     *
     * @param definition 灵元定义
     * @return true 表示该实例为金丝雀版本
     */
    private boolean extractCanaryFlag(LingDefinition definition) {
        if (definition == null || definition.getProperties() == null) {
            return false;
        }
        Object val = definition.getProperties().get("canary");
        if (val == null) {
            return false;
        }
        if (val instanceof Boolean) {
            return (Boolean) val;
        }
        if (val instanceof Number) {
            // 与 DashboardLingSourceResolver.isCanary 对齐：非零即 canary，避免同实例身份判定分裂
            return ((Number) val).intValue() != 0;
        }
        // String 分支同样对齐 sibling：仅认 "true"，不认 "1"——保持 dashboard 单一真源
        return "true".equalsIgnoreCase(String.valueOf(val));
    }

    /**
     * 灵元→首个契约 ID（兜底）解析。
     * <p>
     * 命中 {@link LingServiceRegistry#getContractsByLingId} 取首个契约；
     * native/test 场景或灵元未声明任何契约时返回 null。
     */
    private String resolveContractId(String lingId) {
        if (lingServiceRegistry == null || lingId == null) {
            return null;
        }
        Set<String> contracts = lingServiceRegistry.getContractsByLingId(lingId);
        return contracts.isEmpty() ? null : contracts.iterator().next();
    }

    public TrafficStatsDTO toTrafficStats(LingRuntime runtime) {
        // 流量统计已从 LingRuntime 下沉到 ProviderMetricsCollector / LingHealthMetrics
        // LingRuntime 不再持有 totalRequests / stableRequests / canaryRequests / activeRequests 字段
        // 这里改为从 ProviderMetricsCollector 读取契约二维统计 + LingHealthMetrics 读取活跃计数
        String lingId = runtime.getLingId();
        long total = 0;
        long stable = 0;
        long canary = 0;
        long active = 0;

        // 活跃请求数从 LingHealthMetrics 读取（独立于 LingRuntime）
        if (metricsCollector != null) {
            LingHealthMetrics metrics = metricsCollector.getOrCreate(lingId);
            if (metrics != null) {
                active = metrics.getActiveRequests().get();
            }
        }

        // 累计统计从 ProviderMetricsCollector 读取；按 lingId 切分 stable/canary 维度
        // 灵核 baseline provider（lingId == LingCoreConstants.LINGCORE_LING_ID）计 stable，灵元 provider 计 canary
        if (providerMetricsCollector != null && lingServiceRegistry != null) {
            Set<String> contracts = lingServiceRegistry.getContractsByLingId(lingId);
            for (String contractId : contracts) {
                for (ProviderStats stat : providerMetricsCollector.getStatsByContract(contractId)) {
                    long count = stat.getTotalInvocations();
                    total += count;
                    if (LingCoreConstants.LINGCORE_LING_ID.equals(stat.getLingId())) {
                        stable += count;
                    } else {
                        canary += count;
                    }
                }
            }
        }

        return TrafficStatsDTO.builder()
                .lingId(lingId)
                .totalRequests(total)
                .v1Requests(stable)
                .v2Requests(canary)
                .activeRequests(active)
                .v1Percent(total > 0 ? (stable * 100.0 / total) : 0)
                .v2Percent(total > 0 ? (canary * 100.0 / total) : 0)
                .windowStartTime(runtime.getInstalledAt())
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
                        && !Objects.equals(capability, Capabilities.LING_ENABLE)) {
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
}
