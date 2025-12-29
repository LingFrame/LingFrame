package com.lingframe.core.security;

import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认权限服务实现
 * 职责：管理权限策略，提供鉴权查询，记录审计日志
 */
@Slf4j
public class DefaultPermissionService implements PermissionService {

    // 简单的权限表: Map<PluginId, Map<Capability, AccessType>>
    // 实际生产中应从数据库或配置文件加载
    private final Map<String, Map<String, AccessType>> permissions = new ConcurrentHashMap<>();

    // 全局白名单服务 (例如基础的日志服务)
    private static final String GLOBAL_WHITELIST_PREFIX = "com.lingframe.api.";

    @Override
    public boolean isAllowed(String pluginId, String capability, AccessType accessType) {
        // 1. 白名单放行
        if (pluginId == null || capability.startsWith(GLOBAL_WHITELIST_PREFIX)) {
            return true;
        }

        // 2. 查表鉴权
        boolean allowed = checkInternal(pluginId, capability, accessType);

        // 3. 开发模式兜底
        if (!allowed && LingFrameConfig.isDevMode()) {
            log.warn("==========================================================================");
            log.warn("【开发模式警告】 插件 [{}] 越权访问 [{}] ({})。请在 plugin.yml 中声明: {}",
                    pluginId, capability, accessType, capability);
            log.warn("==========================================================================");
            return true; // 开发模式强制放行
        }

        return allowed;
    }

    private boolean checkInternal(String pluginId, String capability, AccessType accessType) {
        Map<String, AccessType> pluginPerms = permissions.get(pluginId);
        if (pluginPerms == null) return false;
        AccessType granted = pluginPerms.get(capability);
        if (granted == null) return false;

        // WRITE 包含 READ 权限
        if (granted == AccessType.WRITE && accessType == AccessType.READ) return true;

        return granted == accessType;
    }

    @Override
    public void grant(String pluginId, String capability, AccessType accessType) {
        permissions.computeIfAbsent(pluginId, k -> new ConcurrentHashMap<>())
                .put(capability, accessType);
    }

    @Override
    public Object getPermission(String pluginId, String capability) {
        return permissions.getOrDefault(pluginId, Map.of()).get(capability);
    }

    @Override
    public void audit(String pluginId, String capability, String operation, boolean allowed) {
        // 简单日志，主要使用 AuditManager
        // 桥接到 AuditManager，或者仅记录关键的安全日志
        if (!allowed) {
            log.warn("[Security] Access Denied - Plugin: {}, Capability: {}, Operation: {}", pluginId, capability, operation);
        }
    }

    /**
     * 清理插件的权限数据
     */
    @Override
    public void removePlugin(String pluginId) {
        if (permissions.remove(pluginId) != null) {
            log.debug("Removed permissions for plugin: {}", pluginId);
        }
    }
}