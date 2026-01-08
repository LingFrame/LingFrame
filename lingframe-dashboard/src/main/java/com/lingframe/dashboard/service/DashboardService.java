package com.lingframe.dashboard.service;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.config.PluginDefinition;
import com.lingframe.core.enums.PluginStatus;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.loader.PluginManifestLoader;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.core.plugin.PluginRuntime;
import com.lingframe.dashboard.converter.PluginInfoConverter;
import com.lingframe.dashboard.dto.PluginInfoDTO;
import com.lingframe.dashboard.dto.ResourcePermissionDTO;
import com.lingframe.dashboard.dto.TrafficStatsDTO;
import com.lingframe.dashboard.router.CanaryRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
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

    public List<PluginInfoDTO> getAllPluginInfos() {
        return pluginManager.getInstalledPlugins().stream()
                .map(this::getPluginInfo)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public PluginInfoDTO getPluginInfo(String pluginId) {
        PluginRuntime runtime = pluginManager.getRuntime(pluginId);
        if (runtime == null) {
            return null;
        }
        return converter.toDTO(runtime, canaryRouter, governanceRegistry);
    }

    public PluginInfoDTO installPlugin(File file) {
        try {
            PluginDefinition def = PluginManifestLoader.parseDefinition(file);
            if (def == null) {
                throw new IllegalArgumentException("Not a valid plugin package: " + file.getName());
            }
            pluginManager.install(def, file);
            return getPluginInfo(def.getId());
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to install plugin: " + e.getMessage(), e);
        }
    }

    public void uninstallPlugin(String pluginId) {
        try {
            pluginManager.uninstall(pluginId);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to uninstall plugin: " + e.getMessage(), e);
        }
    }

    public PluginInfoDTO reloadPlugin(String pluginId) {
        try {
            pluginManager.reload(pluginId);
            return getPluginInfo(pluginId);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to reload plugin: " + e.getMessage(), e);
        }
    }

    public PluginInfoDTO updateStatus(String pluginId, PluginStatus newStatus) {
        PluginRuntime runtime = pluginManager.getRuntime(pluginId);
        if (runtime == null) {
            throw new IllegalArgumentException("Plugin not found: " + pluginId);
        }

        PluginStatus current = runtime.getStatus();

        // 状态转换验证
        if (!isValidTransition(current, newStatus)) {
            throw new IllegalStateException(
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
                throw new IllegalArgumentException("Unsupported status: " + newStatus);
        }

        return getPluginInfo(pluginId);
    }

    public void setCanaryConfig(String pluginId, int percent, String canaryVersion) {
        PluginRuntime runtime = pluginManager.getRuntime(pluginId);
        if (runtime == null) {
            throw new IllegalArgumentException("Plugin not found: " + pluginId);
        }
        canaryRouter.setCanaryConfig(pluginId, percent, canaryVersion);
    }

    public TrafficStatsDTO getTrafficStats(String pluginId) {
        PluginRuntime runtime = pluginManager.getRuntime(pluginId);
        if (runtime == null) {
            throw new IllegalArgumentException("Plugin not found: " + pluginId);
        }
        return converter.toTrafficStats(runtime);
    }

    public void resetTrafficStats(String pluginId) {
        PluginRuntime runtime = pluginManager.getRuntime(pluginId);
        if (runtime == null) {
            throw new IllegalArgumentException("Plugin not found: " + pluginId);
        }
        runtime.resetTrafficStats();
    }

    public void updatePermissions(String pluginId, ResourcePermissionDTO dto) {
        GovernancePolicy policy = new GovernancePolicy();
        policy.setPermissions(new ArrayList<>());

        policy.getPermissions().add(GovernancePolicy.PermissionRule.builder()
                .methodPattern("*")
                .permissionId(dto.isDbRead() ? "resource:db:read" : "resource:db:read:deny")
                .build());

        policy.getPermissions().add(GovernancePolicy.PermissionRule.builder()
                .methodPattern("*")
                .permissionId(dto.isDbWrite() ? "resource:db:write" : "resource:db:write:deny")
                .build());

        policy.getPermissions().add(GovernancePolicy.PermissionRule.builder()
                .methodPattern("*")
                .permissionId(dto.isCacheRead() ? "resource:cache:read" : "resource:cache:read:deny")
                .build());

        policy.getPermissions().add(GovernancePolicy.PermissionRule.builder()
                .methodPattern("*")
                .permissionId(dto.isCacheWrite() ? "resource:cache:write" : "resource:cache:write:deny")
                .build());

        governanceRegistry.updatePatch(pluginId, policy);
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