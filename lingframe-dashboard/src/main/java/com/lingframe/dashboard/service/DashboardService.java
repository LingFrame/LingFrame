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
                break;
            case LOADED:
                runtime.setStatus(PluginStatus.STOPPING);
                // 执行停止逻辑 (但不卸载)
                runtime.deactivate();
                runtime.setStatus(PluginStatus.LOADED);
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
        // SQL 权限
        if (dto.isDbRead() || dto.isDbWrite()) {
            AccessType type = dto.isDbWrite() ? AccessType.WRITE : AccessType.READ;
            permissionService.grant(pluginId, Capabilities.STORAGE_SQL, type);
        } else {
            permissionService.revoke(pluginId, Capabilities.STORAGE_SQL);
        }

        // 本地缓存权限
        if (dto.isCacheRead() || dto.isCacheWrite()) {
            AccessType type = dto.isCacheWrite() ? AccessType.WRITE : AccessType.READ;
            permissionService.grant(pluginId, Capabilities.CACHE_LOCAL, type);
        } else {
            permissionService.revoke(pluginId, Capabilities.CACHE_LOCAL);
        }

        log.info("Updated permissions for plugin {}: SQL={}/{}, Cache={}/{}",
                pluginId, dto.isDbRead(), dto.isDbWrite(), dto.isCacheRead(), dto.isCacheWrite());
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