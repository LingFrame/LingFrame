package com.lingframe.dashboard.service;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.config.PluginDefinition;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.enums.PluginStatus;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.loader.PluginManifestLoader;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.core.plugin.PluginRuntime;
import com.lingframe.dashboard.converter.PluginInfoConverter;
import com.lingframe.dashboard.dto.PluginInfoDTO;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.api.exception.PluginNotFoundException;
import com.lingframe.core.exception.PluginInstallException;
import com.lingframe.core.exception.ServiceUnavailableException;
import com.lingframe.dashboard.dto.ResourcePermissionDTO;
import com.lingframe.dashboard.dto.TrafficStatsDTO;
import com.lingframe.dashboard.router.CanaryRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class DashboardService {

    private final PluginManager pluginManager;
    private final LocalGovernanceRegistry governanceRegistry;
    private final CanaryRouter canaryRouter;
    private final PluginInfoConverter converter;
    private final PermissionService permissionService;

    public List<PluginInfoDTO> getAllPluginInfos() {
        return pluginManager.getInstalledPlugins().stream()
                .map(pluginManager::getRuntime)
                .filter(Objects::nonNull)
                .map(runtime -> {
                    GovernancePolicy policy = getEffectivePolicy(runtime.getPluginId());
                    return converter.toDTO(runtime, canaryRouter, permissionService, policy);
                })
                .collect(Collectors.toList());
    }

    public PluginInfoDTO getPluginInfo(String pluginId) {
        PluginRuntime runtime = pluginManager.getRuntime(pluginId);
        if (runtime == null) {
            return null;
        }
        GovernancePolicy policy = getEffectivePolicy(pluginId);
        return converter.toDTO(runtime, canaryRouter, permissionService, policy);
    }

    private GovernancePolicy getEffectivePolicy(String pluginId) {
        // 优先获取动态补丁
        GovernancePolicy policy = governanceRegistry.getPatch(pluginId);
        if (policy != null) {
            return policy;
        }
        // 降级使用静态定义
        PluginRuntime runtime = pluginManager.getRuntime(pluginId);
        if (runtime != null && runtime.getInstancePool().getDefault() != null
                && runtime.getInstancePool().getDefault().getDefinition() != null) {
            return runtime.getInstancePool().getDefault().getDefinition().getGovernance();
        }
        return null; // 无策略
    }

    public PluginInfoDTO installPlugin(File file) {
        try {
            PluginDefinition def = PluginManifestLoader.parseDefinition(file);
            if (def == null) {
                throw new InvalidArgumentException("file", "Not a valid plugin package: " + file.getName());
            }
            pluginManager.install(def, file);
            return getPluginInfo(def.getId());
        } catch (Exception e) {
            throw new PluginInstallException("unknown", "Failed to install plugin: " + e.getMessage(), e);
        }
    }

    public void uninstallPlugin(String pluginId) {
        try {
            pluginManager.uninstall(pluginId);
        } catch (Exception e) {
            throw new PluginInstallException(pluginId, "Failed to uninstall plugin: " + e.getMessage(), e);
        }
    }

    public PluginInfoDTO reloadPlugin(String pluginId) {
        try {
            pluginManager.reload(pluginId);
            return getPluginInfo(pluginId);
        } catch (Exception e) {
            throw new PluginInstallException(pluginId, "Failed to reload plugin: " + e.getMessage(), e);
        }
    }

    public PluginInfoDTO updateStatus(String pluginId, PluginStatus newStatus) {
        PluginRuntime runtime = pluginManager.getRuntime(pluginId);
        if (runtime == null) {
            throw new PluginNotFoundException(pluginId);
        }

        PluginStatus current = runtime.getStatus();

        // 状态转换验证
        if (!isValidTransition(current, newStatus)) {
            throw new ServiceUnavailableException(pluginId,
                    String.format("Invalid status transition: %s -> %s", current, newStatus));
        }

        switch (newStatus) {
            case ACTIVE:
                runtime.setStatus(PluginStatus.STARTING);
                // 执行激活逻辑
                runtime.activate();
                runtime.setStatus(PluginStatus.ACTIVE);

                // 初始化治理策略（如果不存在或为空）
                GovernancePolicy policy = governanceRegistry.getPatch(pluginId);
                if (policy == null || policy.getCapabilities() == null || policy.getCapabilities().isEmpty()) {
                    log.info("[Dashboard] Initializing default permissions for plugin: {}", pluginId);

                    // 创建默认权限配置（全部开启）
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
                                    .capability(Capabilities.PLUGIN_ENABLE)
                                    .accessType(AccessType.EXECUTE.name())
                                    .build());

                    if (policy == null) {
                        policy = new GovernancePolicy();
                    }
                    policy.setCapabilities(defaultCapabilities);
                    governanceRegistry.updatePatch(pluginId, policy);

                    // 同步到运行时权限服务
                    permissionService.grant(pluginId, Capabilities.STORAGE_SQL, AccessType.WRITE);
                    permissionService.grant(pluginId, Capabilities.CACHE_LOCAL, AccessType.WRITE);
                    permissionService.grant(pluginId, Capabilities.PLUGIN_ENABLE, AccessType.EXECUTE);

                    log.info("[Dashboard] Default permissions initialized and persisted");
                } else {
                    log.info("[Dashboard] Plugin has governance policy, loading permissions from file");

                    // 从治理策略加载权限并同步到运行时
                    for (GovernancePolicy.CapabilityRule rule : policy.getCapabilities()) {
                        try {
                            AccessType accessType = AccessType.valueOf(rule.getAccessType());
                            permissionService.grant(pluginId, rule.getCapability(), accessType);
                            log.info("[Dashboard] Loaded permission: {} -> {}", rule.getCapability(), accessType);
                        } catch (Exception e) {
                            log.warn("[Dashboard] Failed to load permission: {} -> {}, error: {}",
                                    rule.getCapability(), rule.getAccessType(), e.getMessage());
                        }
                    }
                }
                break;
            case LOADED:
                runtime.setStatus(PluginStatus.STOPPING);
                // 执行停止逻辑 (但不卸载)
                runtime.deactivate();
                runtime.setStatus(PluginStatus.LOADED);
                // 撤销插件启用权限
                permissionService.revoke(pluginId, Capabilities.PLUGIN_ENABLE);
                log.info("[Dashboard] Revoked PLUGIN_ENABLE permission from {}", pluginId);
                break;
            case UNLOADED:
                pluginManager.uninstall(pluginId);
                break;
            default:
                throw new InvalidArgumentException("status", "Unsupported status: " + newStatus);
        }

        return getPluginInfo(pluginId);
    }

    public void setCanaryConfig(String pluginId, int percent, String canaryVersion) {
        PluginRuntime runtime = pluginManager.getRuntime(pluginId);
        if (runtime == null) {
            throw new PluginNotFoundException(pluginId);
        }
        canaryRouter.setCanaryConfig(pluginId, percent, canaryVersion);
    }

    public TrafficStatsDTO getTrafficStats(String pluginId) {
        PluginRuntime runtime = pluginManager.getRuntime(pluginId);
        if (runtime == null) {
            throw new PluginNotFoundException(pluginId);
        }
        return converter.toTrafficStats(runtime);
    }

    public void resetTrafficStats(String pluginId) {
        PluginRuntime runtime = pluginManager.getRuntime(pluginId);
        if (runtime == null) {
            throw new PluginNotFoundException(pluginId);
        }
        runtime.resetTrafficStats();
    }

    public void updatePermissions(String pluginId, ResourcePermissionDTO dto) {
        log.info("========== Starting Permission Update ==========");
        log.info("Plugin ID: {}", pluginId);
        log.info("Received permissions: dbRead={}, dbWrite={}, cacheRead={}, cacheWrite={}",
                dto.isDbRead(), dto.isDbWrite(), dto.isCacheRead(), dto.isCacheWrite());

        // 1. 计算目标权限
        AccessType sqlAccess = determineAccessType(dto.isDbRead(), dto.isDbWrite());
        AccessType cacheAccess = determineAccessType(dto.isCacheRead(), dto.isCacheWrite());

        log.info("Calculated permissions: SQL={}, Cache={}", sqlAccess, cacheAccess);

        // 2. 同步到运行时权限服务
        permissionService.grant(pluginId, Capabilities.STORAGE_SQL, sqlAccess);
        permissionService.grant(pluginId, Capabilities.CACHE_LOCAL, cacheAccess);

        // 3. 同步到治理策略并持久化
        GovernancePolicy policy = governanceRegistry.getPatch(pluginId);
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
        ruleMap.put(Capabilities.PLUGIN_ENABLE, GovernancePolicy.CapabilityRule.builder()
                .capability(Capabilities.PLUGIN_ENABLE)
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
            for (String targetPluginId : dto.getIpcServices()) {
                String capability = "ipc:" + targetPluginId;
                ruleMap.put(capability, GovernancePolicy.CapabilityRule.builder()
                        .capability(capability)
                        .accessType(AccessType.EXECUTE.name()) // IPC 默认为 EXECUTE
                        .build());
                // 同时授权到运行时
                permissionService.grant(pluginId, capability, AccessType.EXECUTE);
            }
        }

        // 4. 设置回策略
        policy.setCapabilities(new ArrayList<>(ruleMap.values()));
        governanceRegistry.updatePatch(pluginId, policy);

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

    private boolean isValidTransition(PluginStatus from, PluginStatus to) {
        if (from == null || to == null) {
            return false;
        }

        switch (from) {
            case UNLOADED:
                return to == PluginStatus.LOADING || to == PluginStatus.LOADED;
            case LOADED:
                return to == PluginStatus.ACTIVE || to == PluginStatus.UNLOADED;
            case ACTIVE:
                return to == PluginStatus.LOADED || to == PluginStatus.UNLOADED;
            default:
                return false;
        }
    }
}