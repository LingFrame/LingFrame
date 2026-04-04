package com.lingframe.dashboard.service;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.fsm.TransitionResult;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.governance.GovernancePermissionSynchronizer;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.loader.LingManifestLoader;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingUninstallResult;
import com.lingframe.dashboard.converter.LingInfoConverter;
import com.lingframe.dashboard.dto.LingInfoDTO;
import com.lingframe.dashboard.dto.InvocationGovernanceDTO;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.api.exception.LingNotFoundException;
import com.lingframe.core.exception.LingInstallException;
import com.lingframe.dashboard.dto.LeakRiskReportDTO;
import com.lingframe.dashboard.dto.LingUninstallResultDTO;
import com.lingframe.dashboard.dto.ResourcePermissionDTO;
import com.lingframe.dashboard.dto.TrafficStatsDTO;
import com.lingframe.core.router.CanaryRouter;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class DashboardService {

    /**
     * 灵元生命周期事件
     */
    @Data
    public static class LifecycleEvent {
        private String id;
        private String lingId;
        private String version;
        private String type;
        private String title;
        private String description;
        private long timestamp;
        
        public LifecycleEvent(String lingId, String version, String type, String title, String description) {
            this.id = UUID.randomUUID().toString();
            this.lingId = lingId;
            this.version = version;
            this.type = type;
            this.title = title;
            this.description = description;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private final LingFrameConfig lingFrameConfig;
    private final LingLifecycleEngine lifecycleEngine;
    private final LingRepository lingRepository;
    private final LocalGovernanceRegistry governanceRegistry;
    private final CanaryRouter canaryRouter;
    private final LingInfoConverter converter;
    private final PermissionService permissionService;
    private final RuntimeCoordinator runtimeCoordinator;
    
    // 存储生命周期事件的列表
    private final List<LifecycleEvent> lifecycleEvents = Collections.synchronizedList(new ArrayList<>());

    public List<LingInfoDTO> getAllLingInfos() {
        return lingRepository.getAllRuntimes().stream()
                .filter(Objects::nonNull)
                .map(runtime -> {
                    GovernancePolicy policy = getEffectivePolicy(runtime.getLingId());
                    return converter.toDTO(runtime, canaryRouter, permissionService, policy);
                })
                .collect(Collectors.toList());
    }

    public LingInfoDTO getLingInfo(String lingId) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            return null;
        }
        GovernancePolicy policy = getEffectivePolicy(lingId);
        return converter.toDTO(runtime, canaryRouter, permissionService, policy);
    }

    private GovernancePolicy getEffectivePolicy(String lingId) {
        GovernancePolicy staticPolicy = getStaticPolicy(lingId);
        GovernancePolicy patch = governanceRegistry.getPatch(lingId);
        if (staticPolicy == null && patch == null) {
            return null;
        }
        return GovernancePolicy.merge(staticPolicy, patch);
    }

    private GovernancePolicy getStaticPolicy(String lingId) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime != null && runtime.getInstancePool().getDefault() != null
                && runtime.getInstancePool().getDefault().getDefinition() != null) {
            GovernancePolicy governance = runtime.getInstancePool().getDefault().getDefinition().getGovernance();
            return governance == null ? null : governance.copy();
        }
        return null;
    }

    private GovernancePolicy getPatchForUpdate(String lingId) {
        GovernancePolicy patch = governanceRegistry.getPatch(lingId);
        return patch == null ? new GovernancePolicy() : patch.copy();
    }

    private void persistPolicyPatch(String lingId, GovernancePolicy patch) {
        governanceRegistry.updatePatch(lingId, patch);
        syncPermissionsFromPolicy(lingId, getEffectivePolicy(lingId));
    }

    public LingInfoDTO installLing(File file) {
        try {
            LingDefinition def = LingManifestLoader.parseDefinition(file);
            if (def == null) {
                throw new InvalidArgumentException("file", "Not a valid ling package: " + file.getName());
            }
            boolean isCanary = isCanary(def);
            lifecycleEngine.deploy(def, file, !isCanary, Collections.emptyMap());
            addLifecycleEvent(def.getId(), def.getVersion(), "READY", "灵元安装完成", "灵元 " + def.getId() + " 版本 " + def.getVersion() + " 安装成功并准备就绪");
            return getLingInfo(def.getId());
        } catch (Exception e) {
            throw new LingInstallException("unknown", "Failed to install ling: " + e.getMessage(), e);
        }
    }

    private boolean isCanary(LingDefinition def) {
        if (def == null || def.getProperties() == null) {
            return false;
        }
        Object value = def.getProperties().get("canary");
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

    public LingUninstallResultDTO uninstallLing(String lingId) {
        try {
            canaryRouter.removeCanaryConfig(lingId);
            LingUninstallResult result = lifecycleEngine.undeployWithReport(lingId);
            if (result.isUninstallTriggered()) {
                addLifecycleEvent(lingId, "", "DEAD", "灵元完全卸载", "灵元 " + lingId + " 已完全卸载，所有版本均已移除");
            }
            return toUninstallResultDTO(result);
        } catch (Exception e) {
            throw new LingInstallException(lingId, "Failed to uninstall ling: " + e.getMessage(), e);
        }
    }

    public LingUninstallResultDTO uninstallLing(String lingId, String version) {
        try {
            canaryRouter.removeCanaryConfig(lingId);
            LingUninstallResult result = lifecycleEngine.undeployWithReport(lingId, version);
            if (result.isUninstallTriggered()) {
                addLifecycleEvent(lingId, version, "UNLOAD", "灵元版本卸载", "灵元 " + lingId + " 版本 " + version + " 已卸载");
            }
            return toUninstallResultDTO(result);
        } catch (Exception e) {
            throw new LingInstallException(lingId,
                    "Failed to uninstall ling version " + version + ": " + e.getMessage(), e);
        }
    }

    public LingInfoDTO reloadLing(String lingId, String version) {
        try {
            LingRuntime runtime = lingRepository.getRuntime(lingId);
            if (runtime == null) {
                throw new LingNotFoundException(lingId);
            }

            LingInstance target = version != null
                    ? runtime.getInstancePool().getInstance(version)
                    : selectStableInstance(runtime);
            if (target == null) {
                throw new LingInstallException(lingId, "No available instance to reload", null);
            }

            String targetVersion = target.getVersion();
            String baseVersion = targetVersion;
            int reloadIdx = targetVersion.indexOf("-reload-");
            if (reloadIdx > 0) {
                baseVersion = targetVersion.substring(0, reloadIdx);
            }
            File source = resolveSourceFile(lingId, baseVersion);
            if (source == null) {
                throw new LingInstallException(lingId,
                        "Source file not found for " + lingId + ":" + targetVersion, null);
            }

            LingDefinition def = LingManifestLoader.parseDefinition(source);
            if (def == null) {
                throw new LingInstallException(lingId, "Invalid ling package: " + source.getAbsolutePath(), null);
            }

            // 重载时保持原实例的默认/灰度角色与标签
            boolean wasDefault = runtime.getInstancePool().getDefault() == target;
            Map<String, String> labels = new HashMap<>(target.getLabels());

            // 重载时改版本号：baseVersion + "-reload-{n}"
            String reloadVersion = buildReloadVersion(runtime, baseVersion);
            def.setVersion(reloadVersion);
            markReload(def, labels, reloadVersion);

            // 两阶段热重载：先新建，再切流，最后卸载旧实例
            lifecycleEngine.deployForReload(def, source, wasDefault, labels);

            LingInstance newInstance = runtime.getInstancePool().getInstance(reloadVersion);
            if (newInstance == null) {
                throw new LingInstallException(lingId, "Hot reload failed: new instance not found", null);
            }

            lifecycleEngine.undeploy(lingId, target);
            addLifecycleEvent(lingId, reloadVersion, "RELOAD", "灵元热重载", "灵元 " + lingId + " 版本 " + targetVersion + " 已热重载为 " + reloadVersion);

            return getLingInfo(lingId);
        } catch (Exception e) {
            throw new LingInstallException(lingId, "Failed to reload ling: " + e.getMessage(), e);
        }
    }

    public LingInfoDTO updateStatus(String lingId, RuntimeStatus newStatus, String version) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingNotFoundException(lingId);
        }

        RuntimeStatus currentStatus = runtime.currentStatus();
        log.info("[Dashboard] Requesting status transition for ling {}: {} -> {}", lingId, currentStatus, newStatus);

        switch (newStatus) {
            case ACTIVE:
                TransitionResult<RuntimeStatus> activeResult = runtimeCoordinator.transition(lingId, RuntimeStatus.ACTIVE);
                if (!activeResult.isSuccess()) {
                    String errorMsg = String.format("Cannot transition %s to ACTIVE from %s: %s", 
                            lingId, currentStatus, activeResult.code());
                    log.warn("[Dashboard] {}", errorMsg);
                    throw new IllegalStateException(errorMsg);
                }
                log.info("[Dashboard] State transitioned to ACTIVE for ling: {}", lingId);
                addLifecycleEvent(lingId, version, "ACTIVE", "灵元激活", "灵元 " + lingId + " 已激活并开始处理请求");

                GovernancePolicy effectivePolicy = getEffectivePolicy(lingId);
                if (effectivePolicy == null
                        || effectivePolicy.getCapabilities() == null
                        || effectivePolicy.getCapabilities().isEmpty()) {
                    log.info("[Dashboard] Initializing default permissions for ling: {}", lingId);

                    List<GovernancePolicy.CapabilityRule> defaultCapabilities = Arrays.asList(
                            GovernancePolicy.CapabilityRule.builder()
                                    .capability(Capabilities.STORAGE_SQL)
                                    .accessType(AccessType.WRITE.name())
                                    .build(),
                            GovernancePolicy.CapabilityRule.builder()
                                    .capability(Capabilities.CACHE_LOCAL)
                                    .accessType(AccessType.WRITE.name())
                                    .build(),
                            GovernancePolicy.CapabilityRule.builder()
                                    .capability(Capabilities.Ling_ENABLE)
                                    .accessType(AccessType.EXECUTE.name())
                                    .build());

                    GovernancePolicy patch = getPatchForUpdate(lingId);
                    patch.setCapabilities(defaultCapabilities);
                    governanceRegistry.updatePatch(lingId, patch);
                    effectivePolicy = getEffectivePolicy(lingId);
                }

                syncPermissionsFromPolicy(lingId, effectivePolicy);
                break;
            case INACTIVE:
                TransitionResult<RuntimeStatus> inactiveResult = runtimeCoordinator.transition(lingId, RuntimeStatus.INACTIVE);
                if (!inactiveResult.isSuccess()) {
                    String errorMsg = String.format("Cannot transition %s to INACTIVE from %s: %s", 
                            lingId, currentStatus, inactiveResult.code());
                    log.warn("[Dashboard] {}", errorMsg);
                    throw new IllegalStateException(errorMsg);
                }
                log.info("[Dashboard] State transitioned to INACTIVE for ling: {}", lingId);
                addLifecycleEvent(lingId, version, "STOPPING", "灵元停用", "灵元 " + lingId + " 已停用，不再接受新请求");

                permissionService.revoke(lingId, Capabilities.Ling_ENABLE);
                log.info("[Dashboard] Revoked Ling_ENABLE permission from {}, ling deactivated", lingId);
                break;
            case RECOVERING:
                lifecycleEngine.recover(lingId, version);
                log.info("[Dashboard] Recovery triggered for ling: {} version: {}", lingId, version);
                addLifecycleEvent(lingId, version, "RECOVERING", "灵元恢复中",
                        version == null
                                ? "灵元 " + lingId + " 已进入受控恢复流程"
                                : "灵元 " + lingId + " 版本 " + version + " 已进入受控恢复流程");
                addLifecycleEvent(lingId, version, "ACTIVE", "灵元恢复完成",
                        version == null
                                ? "灵元 " + lingId + " 已恢复到可服务状态"
                                : "灵元 " + lingId + " 版本 " + version + " 已恢复到可服务状态");
                break;
            case REMOVED:
                lifecycleEngine.undeploy(lingId);
                break;
            default:
                throw new InvalidArgumentException("status", "Unsupported status: " + newStatus);
        }

        return getLingInfo(lingId);
    }

    public void setCanaryConfig(String lingId, int percent, String canaryVersion) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingNotFoundException(lingId);
        }
        canaryRouter.setCanaryConfig(lingId, percent, canaryVersion);
    }

    public TrafficStatsDTO getTrafficStats(String lingId) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingNotFoundException(lingId);
        }
        return converter.toTrafficStats(runtime);
    }

    public void resetTrafficStats(String lingId) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingNotFoundException(lingId);
        }
        runtime.resetTrafficStats();
    }
    
    public List<LifecycleEvent> getLifecycleEvents(String lingId) {
        if (lingId == null || lingId.isEmpty()) {
            return new ArrayList<>(lifecycleEvents);
        } else {
            return lifecycleEvents.stream()
                    .filter(event -> lingId.equals(event.getLingId()))
                    .collect(Collectors.toList());
        }
    }
    
    private void addLifecycleEvent(String lingId, String version, String type, String title, String description) {
        LifecycleEvent event = new LifecycleEvent(lingId, version, type, title, description);
        lifecycleEvents.add(event);
        // 限制事件数量，只保留最近的1000条
        if (lifecycleEvents.size() > 1000) {
            lifecycleEvents.remove(0);
        }
    }

    public void updatePermissions(String lingId, ResourcePermissionDTO dto) {
        log.info("========== Starting Permission Update ==========");
        log.info("Ling ID: {}", lingId);
        log.info("Received permissions: dbRead={}, dbWrite={}, cacheRead={}, cacheWrite={}",
                dto.isDbRead(), dto.isDbWrite(), dto.isCacheRead(), dto.isCacheWrite());

        // 1. 计算目标权限
        AccessType sqlAccess = determineAccessType(dto.isDbRead(), dto.isDbWrite());
        AccessType cacheAccess = determineAccessType(dto.isCacheRead(), dto.isCacheWrite());

        log.info("Calculated permissions: SQL={}, Cache={}", sqlAccess, cacheAccess);

        // 2. 同步到治理策略并持久化
        GovernancePolicy policy = getPatchForUpdate(lingId);

        // 构建/合并 capabilities 列表
        Map<String, GovernancePolicy.CapabilityRule> ruleMap = new HashMap<>();

        // 1. 加载现有规则
        if (policy.getCapabilities() != null) {
            for (GovernancePolicy.CapabilityRule rule : policy.getCapabilities()) {
                ruleMap.put(rule.getCapability(), rule);
            }
        }

        // 2. 更新或添加受管规则 (SQL/Cache/Enable)
        ruleMap.put(Capabilities.STORAGE_SQL, GovernancePolicy.CapabilityRule.builder()
                .capability(Capabilities.STORAGE_SQL)
                .accessType(sqlAccess.name())
                .build());
        ruleMap.put(Capabilities.CACHE_LOCAL, GovernancePolicy.CapabilityRule.builder()
                .capability(Capabilities.CACHE_LOCAL)
                .accessType(cacheAccess.name())
                .build());
        ruleMap.put(Capabilities.Ling_ENABLE, GovernancePolicy.CapabilityRule.builder()
                .capability(Capabilities.Ling_ENABLE)
                .accessType(AccessType.EXECUTE.name())
                .build());

        // 3. 处理 IPC 权限更新 (如果前端传递了 ipcServices)
        if (dto.getIpcServices() != null) {
            // 先清理旧的 IPC 权限 (假定前端发来的是全量 IPC 列表)
            // 先找出所有 key，避免并发修改异常
            List<String> toRemove = new ArrayList<>();
            for (String key : ruleMap.keySet()) {
                if (key.startsWith("ipc:")) {
                    toRemove.add(key);
                }
            }
            toRemove.forEach(ruleMap::remove);

            // 添加新的 IPC 权限
            for (String targetLingId : dto.getIpcServices()) {
                String capability = "ipc:" + targetLingId;
                ruleMap.put(capability, GovernancePolicy.CapabilityRule.builder()
                        .capability(capability)
                        .accessType(AccessType.EXECUTE.name()) // IPC 默认为 EXECUTE
                        .build());
            }
        }

        // 4. 设置回策略并同步到运行时
        policy.setCapabilities(new ArrayList<>(ruleMap.values()));
        persistPolicyPatch(lingId, policy);

        log.info("Permission update completed and persisted");
        log.info("========================================");
    }

    /**
     * 根据读写标志确定访问类型
     * <p>
     * 规则：
     * - 都关闭：NONE（明确拒绝）
     * - 只读：READ
     * - 只写或读写：WRITE（因为 WRITE 包含 READ）
     * </p>
     */
    private AccessType determineAccessType(boolean read, boolean write) {
        if (write) {
            // 如果有写权限，始终授予 WRITE（自动包含 READ）
            return AccessType.WRITE;
        } else if (read) {
            // 如果只有读权限，授予 READ
            return AccessType.READ;
        }
        // 两者都没有，明确拒绝
        return AccessType.NONE;
    }

    /**
     * 以治理策略为唯一来源，刷新运行时权限表。
     */
    public void updateGovernancePolicy(String lingId, GovernancePolicy policy) {
        GovernancePolicy mergedPatch = GovernancePolicy.merge(getPatchForUpdate(lingId), policy);
        persistPolicyPatch(lingId, mergedPatch);
    }

    /**
     * 更新调用治理配置。
     * 这里只更新 invocation 分区，不干扰 capabilities / permissions / audits。
     */
    public InvocationGovernanceDTO updateInvocationGovernance(String lingId, InvocationGovernanceDTO dto) {
        GovernancePolicy patch = getPatchForUpdate(lingId);
        GovernancePolicy.InvocationPolicy invocation = patch.getInvocation();
        if (invocation == null) {
            invocation = new GovernancePolicy.InvocationPolicy();
        }

        invocation.setTimeoutMs(dto.getTimeoutMs());
        invocation.setRateLimitPerSecond(dto.getRateLimitPerSecond());
        invocation.setMaxConcurrentThreads(dto.getMaxConcurrentThreads());
        invocation.setRetryCount(dto.getRetryCount());
        invocation.setFallbackValue(dto.getFallbackValue());
        invocation.setCpuBudgetMsPerMinute(dto.getCpuBudgetMsPerMinute());
        invocation.setMemoryBudgetMb(dto.getMemoryBudgetMb());
        patch.setInvocation(invocation);

        persistPolicyPatch(lingId, patch);
        return getInvocationGovernance(lingId);
    }

    public InvocationGovernanceDTO getInvocationGovernance(String lingId) {
        GovernancePolicy effectivePolicy = getEffectivePolicy(lingId);
        GovernancePolicy.InvocationPolicy invocation =
                effectivePolicy == null ? null : effectivePolicy.getInvocation();
        return InvocationGovernanceDTO.builder()
                .timeoutMs(invocation == null ? null : invocation.getTimeoutMs())
                .rateLimitPerSecond(invocation == null ? null : invocation.getRateLimitPerSecond())
                .maxConcurrentThreads(invocation == null ? null : invocation.getMaxConcurrentThreads())
                .retryCount(invocation == null ? null : invocation.getRetryCount())
                .fallbackValue(invocation == null ? null : invocation.getFallbackValue())
                .cpuBudgetMsPerMinute(invocation == null ? null : invocation.getCpuBudgetMsPerMinute())
                .memoryBudgetMb(invocation == null ? null : invocation.getMemoryBudgetMb())
                .build();
    }

    private void syncPermissionsFromPolicy(String lingId, GovernancePolicy policy) {
        GovernancePermissionSynchronizer.syncPolicy(lingId, policy, permissionService);
    }

    private LingInstance selectStableInstance(LingRuntime runtime) {
        if (runtime == null) {
            return null;
        }
        for (LingInstance instance : runtime.getInstancePool().getActiveInstances()) {
            if (!isCanary(instance)) {
                return instance;
            }
        }
        LingInstance fallback = runtime.getInstancePool().getDefault();
        if (fallback != null) {
            return fallback;
        }
        List<LingInstance> active = runtime.getInstancePool().getActiveInstances();
        return active.isEmpty() ? null : active.get(0);
    }

    private File resolveSourceFile(String lingId, String version) {
        File devFile = findFromRoots(lingId, version);
        if (devFile != null) {
            return devFile;
        }
        File homeFile = findFromHome(lingId, version);
        if (homeFile != null) {
            return homeFile;
        }
        return null;
    }

    private File findFromRoots(String lingId, String version) {
        if (lingFrameConfig == null || !lingFrameConfig.isDevMode()) {
            return null;
        }
        List<String> roots = lingFrameConfig.getLingRoots();
        if (roots == null || roots.isEmpty()) {
            return null;
        }
        for (String root : roots) {
            String realPath = root + File.separator + "/target/classes";
            File realFile = new File(realPath);
            if (!realFile.exists()) {
                continue;
            }
            LingDefinition def = LingManifestLoader.parseDefinition(realFile);
            if (def != null && lingId.equals(def.getId()) && version.equals(def.getVersion())) {
                return realFile;
            }
        }
        return null;
    }

    private File findFromHome(String lingId, String version) {
        if (lingFrameConfig == null || lingFrameConfig.getLingHome() == null) {
            return null;
        }
        File home = new File(lingFrameConfig.getLingHome());
        if (!home.exists() || !home.isDirectory()) {
            return null;
        }
        File[] files = home.listFiles();
        if (files == null) {
            return null;
        }
        for (File file : files) {
            LingDefinition def = LingManifestLoader.parseDefinition(file);
            if (def != null && lingId.equals(def.getId()) && version.equals(def.getVersion())) {
                return file;
            }
        }
        return null;
    }

    private boolean isCanary(LingInstance instance) {
        if (instance == null || instance.getDefinition() == null) {
            return false;
        }
        return isCanary(instance.getDefinition());
    }

    private String buildReloadVersion(LingRuntime runtime, String baseVersion) {
        int max = 0;
        String prefix = baseVersion + "-reload-";
        for (LingInstance instance : runtime.getInstancePool().getAllInstances()) {
            String v = instance.getVersion();
            if (v != null && v.startsWith(prefix)) {
                String suffix = v.substring(prefix.length());
                try {
                    int n = Integer.parseInt(suffix);
                    if (n > max) {
                        max = n;
                    }
                } catch (NumberFormatException ignore) {
                    // 忽略格式不合法的重载版本号
                }
            }
        }
        return prefix + (max + 1);
    }

    private void markReload(LingDefinition def, Map<String, String> labels, String reloadVersion) {
        if (labels != null) {
            labels.put("reload", "true");
            labels.put("reloadVersion", reloadVersion);
        }
        Map<String, Object> props = def.getProperties();
        if (props == null) {
            props = new HashMap<>();
            def.setProperties(props);
        }
        props.put("reload", true);
        props.put("reloadVersion", reloadVersion);
    }

    private LingUninstallResultDTO toUninstallResultDTO(LingUninstallResult result) {
        List<LeakRiskReportDTO> reports = result.getReports().stream()
                .map(report -> LeakRiskReportDTO.builder()
                        .lingId(report.getLingId())
                        .version(report.getVersion())
                        .level(report.getLevel())
                        .summary(report.getSummary())
                        .details(report.getDetails())
                        .checker(report.getChecker())
                        .timestamp(report.getTimestamp())
                        .build())
                .collect(Collectors.toList());

        return LingUninstallResultDTO.builder()
                .lingId(result.getLingId())
                .version(result.getVersion())
                .uninstallTriggered(result.isUninstallTriggered())
                .overallRiskLevel(result.getOverallRiskLevel())
                .reports(reports)
                .build();
    }

}
