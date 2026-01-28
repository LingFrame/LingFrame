package com.lingframe.dashboard.service;

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
import java.util.List;
import java.util.Objects;
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
                .map(runtime -> converter.toDTO(runtime, canaryRouter, permissionService))
                .collect(Collectors.toList());
    }

    public PluginInfoDTO getPluginInfo(String pluginId) {
        PluginRuntime runtime = pluginManager.getRuntime(pluginId);
        if (runtime == null) {
            return null;
        }
        return converter.toDTO(runtime, canaryRouter, permissionService);
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
                var policy = governanceRegistry.getPatch(pluginId);
                if (policy == null || policy.getCapabilities() == null || policy.getCapabilities().isEmpty()) {
                    log.info("[Dashboard] 初始化插件默认权限配置: {}", pluginId);

                    // 创建默认权限配置（全部开启）
                    java.util.List<com.lingframe.api.config.GovernancePolicy.CapabilityRule> defaultCapabilities = java.util.Arrays
                            .asList(
                                    com.lingframe.api.config.GovernancePolicy.CapabilityRule.builder()
                                            .capability(Capabilities.STORAGE_SQL)
                                            .accessType(AccessType.WRITE.name())
                                            .build(),
                                    com.lingframe.api.config.GovernancePolicy.CapabilityRule.builder()
                                            .capability(Capabilities.CACHE_LOCAL)
                                            .accessType(AccessType.WRITE.name())
                                            .build(),
                                    com.lingframe.api.config.GovernancePolicy.CapabilityRule.builder()
                                            .capability(Capabilities.PLUGIN_ENABLE)
                                            .accessType(AccessType.EXECUTE.name())
                                            .build());

                    if (policy == null) {
                        policy = new com.lingframe.api.config.GovernancePolicy();
                    }
                    policy.setCapabilities(defaultCapabilities);
                    governanceRegistry.updatePatch(pluginId, policy);

                    // 同步到运行时权限服务
                    permissionService.grant(pluginId, Capabilities.STORAGE_SQL, AccessType.WRITE);
                    permissionService.grant(pluginId, Capabilities.CACHE_LOCAL, AccessType.WRITE);
                    permissionService.grant(pluginId, Capabilities.PLUGIN_ENABLE, AccessType.EXECUTE);

                    log.info("[Dashboard] 默认权限已初始化并持久化");
                } else {
                    log.info("[Dashboard] 插件已有治理策略，从文件加载权限配置");

                    // 从治理策略加载权限并同步到运行时
                    for (var rule : policy.getCapabilities()) {
                        try {
                            AccessType accessType = AccessType.valueOf(rule.getAccessType());
                            permissionService.grant(pluginId, rule.getCapability(), accessType);
                            log.info("[Dashboard] 已加载权限: {} -> {}", rule.getCapability(), accessType);
                        } catch (Exception e) {
                            log.warn("[Dashboard] 加载权限失败: {} -> {}, 错误: {}",
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
        log.info("========== 开始更新权限 ==========");
        log.info("插件ID: {}", pluginId);
        log.info("接收到的权限: dbRead={}, dbWrite={}, cacheRead={}, cacheWrite={}",
                dto.isDbRead(), dto.isDbWrite(), dto.isCacheRead(), dto.isCacheWrite());

        // 1. 计算目标权限
        AccessType sqlAccess = determineAccessType(dto.isDbRead(), dto.isDbWrite());
        AccessType cacheAccess = determineAccessType(dto.isCacheRead(), dto.isCacheWrite());

        log.info("计算后的权限: SQL={}, Cache={}", sqlAccess, cacheAccess);

        // 2. 同步到运行时权限服务
        permissionService.grant(pluginId, Capabilities.STORAGE_SQL, sqlAccess);
        permissionService.grant(pluginId, Capabilities.CACHE_LOCAL, cacheAccess);

        // 3. 同步到治理策略并持久化
        var policy = governanceRegistry.getPatch(pluginId);
        if (policy == null) {
            policy = new com.lingframe.api.config.GovernancePolicy();
        }

        // 构建 capabilities 列表
        java.util.List<com.lingframe.api.config.GovernancePolicy.CapabilityRule> capabilities = java.util.Arrays.asList(
                com.lingframe.api.config.GovernancePolicy.CapabilityRule.builder()
                        .capability(Capabilities.STORAGE_SQL)
                        .accessType(sqlAccess.name())
                        .build(),
                com.lingframe.api.config.GovernancePolicy.CapabilityRule.builder()
                        .capability(Capabilities.CACHE_LOCAL)
                        .accessType(cacheAccess.name())
                        .build(),
                com.lingframe.api.config.GovernancePolicy.CapabilityRule.builder()
                        .capability(Capabilities.PLUGIN_ENABLE)
                        .accessType(AccessType.EXECUTE.name())
                        .build());

        policy.setCapabilities(capabilities);
        governanceRegistry.updatePatch(pluginId, policy);

        log.info("权限更新完成并已持久化");
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
        // 简化的状态机验证
        return switch (from) {
            case UNLOADED -> to == PluginStatus.LOADING || to == PluginStatus.LOADED;
            case LOADED -> to == PluginStatus.ACTIVE || to == PluginStatus.UNLOADED;
            case ACTIVE -> to == PluginStatus.LOADED || to == PluginStatus.UNLOADED;
            default -> false;
        };
    }
}