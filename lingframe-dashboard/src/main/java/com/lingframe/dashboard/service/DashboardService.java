package com.lingframe.dashboard.service;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.loader.LingManifestLoader;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.dashboard.converter.LingInfoConverter;
import com.lingframe.dashboard.dto.LingInfoDTO;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.api.exception.LingNotFoundException;
import com.lingframe.core.exception.LingInstallException;
import com.lingframe.dashboard.dto.ResourcePermissionDTO;
import com.lingframe.dashboard.dto.TrafficStatsDTO;
import com.lingframe.core.router.CanaryRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class DashboardService {

    private final LingFrameConfig lingFrameConfig;
    private final LingLifecycleEngine lifecycleEngine;
    private final LingRepository lingRepository;
    private final LocalGovernanceRegistry governanceRegistry;
    private final CanaryRouter canaryRouter;
    private final LingInfoConverter converter;
    private final PermissionService permissionService;

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
        // 优先获取动态补丁
        GovernancePolicy policy = governanceRegistry.getPatch(lingId);
        if (policy != null) {
            return policy;
        }
        // 降级使用静态定义
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime != null && runtime.getInstancePool().getDefault() != null
                && runtime.getInstancePool().getDefault().getDefinition() != null) {
            return runtime.getInstancePool().getDefault().getDefinition().getGovernance();
        }
        return null; // 无策略
    }

    public LingInfoDTO installLing(File file) {
        try {
            LingDefinition def = LingManifestLoader.parseDefinition(file);
            if (def == null) {
                throw new InvalidArgumentException("file", "Not a valid ling package: " + file.getName());
            }
            boolean isCanary = isCanary(def);
            lifecycleEngine.deploy(def, file, !isCanary, Collections.emptyMap());
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

    public void uninstallLing(String lingId) {
        try {
            canaryRouter.removeCanaryConfig(lingId);
            lifecycleEngine.undeploy(lingId);
        } catch (Exception e) {
            throw new LingInstallException(lingId, "Failed to uninstall ling: " + e.getMessage(), e);
        }
    }

    public void uninstallLing(String lingId, String version) {
        try {
            canaryRouter.removeCanaryConfig(lingId);
            lifecycleEngine.undeploy(lingId, version);
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
            File source = resolveSourceFile(lingId, targetVersion);
            if (source == null) {
                throw new LingInstallException(lingId,
                        "Source file not found for " + lingId + ":" + targetVersion, null);
            }

            LingDefinition def = LingManifestLoader.parseDefinition(source);
            if (def == null) {
                throw new LingInstallException(lingId, "Invalid ling package: " + source.getAbsolutePath(), null);
            }

            boolean isCanary = isCanary(def);
            lifecycleEngine.undeploy(lingId, targetVersion);
            lifecycleEngine.deploy(def, source, !isCanary, Collections.emptyMap());

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

        if (version != null && !version.isEmpty()) {
            if (newStatus == RuntimeStatus.INACTIVE) {
                lifecycleEngine.pauseVersion(lingId, version);
                return getLingInfo(lingId);
            }
            if (newStatus == RuntimeStatus.ACTIVE) {
                lifecycleEngine.resumeVersion(lingId, version);
                return getLingInfo(lingId);
            }
        }

        switch (newStatus) {
            case ACTIVE:
                // 执行状态机转换：INACTIVE -> ACTIVE
                runtime.getStateMachine().transition(RuntimeStatus.ACTIVE);
                log.info("[Dashboard] State transitioned to ACTIVE for ling: {}", lingId);

                // 初始化治理策略并同步权限到 PermissionService
                GovernancePolicy policy = governanceRegistry.getPatch(lingId);
                if (policy == null || policy.getCapabilities() == null || policy.getCapabilities().isEmpty()) {
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

                    if (policy == null) {
                        policy = new GovernancePolicy();
                    }
                    policy.setCapabilities(defaultCapabilities);
                    governanceRegistry.updatePatch(lingId, policy);
                }

                syncPermissionsFromPolicy(lingId, policy);
                break;
            case INACTIVE:
                // 执行状态机转换，禁止新请求进入
                runtime.getStateMachine().transition(RuntimeStatus.INACTIVE);
                log.info("[Dashboard] State transitioned to INACTIVE for ling: {}", lingId);

                // 同步撤销执行权限，保持权限侧一致
                permissionService.revoke(lingId, Capabilities.Ling_ENABLE);
                log.info("[Dashboard] Revoked Ling_ENABLE permission from {}, ling deactivated", lingId);
                break;
            case REMOVED:
                // 彻底卸载灵元
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
        GovernancePolicy policy = governanceRegistry.getPatch(lingId);
        if (policy == null) {
            policy = new GovernancePolicy();
        }

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
        governanceRegistry.updatePatch(lingId, policy);
        syncPermissionsFromPolicy(lingId, policy);

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
        governanceRegistry.updatePatch(lingId, policy);
        syncPermissionsFromPolicy(lingId, policy);
    }

    private void syncPermissionsFromPolicy(String lingId, GovernancePolicy policy) {
        // 清空该灵元的运行时权限，避免旧权限残留
        permissionService.removeLing(lingId);

        if (policy == null || policy.getCapabilities() == null) {
            return;
        }

        for (GovernancePolicy.CapabilityRule rule : policy.getCapabilities()) {
            try {
                AccessType accessType = AccessType.valueOf(rule.getAccessType());
                permissionService.grant(lingId, rule.getCapability(), accessType);
                log.info("[Dashboard] Loaded permission: {} -> {}", rule.getCapability(), accessType);
            } catch (Exception e) {
                log.warn("[Dashboard] Failed to load permission: {} -> {}, error: {}",
                        rule.getCapability(), rule.getAccessType(), e.getMessage());
            }
        }
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

}
