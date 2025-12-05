package com.lingframe.api.security;

import com.lingframe.api.security.AccessType;

/**
 * Core 提供 - 权限查询服务
 * 负责检查插件是否有某项权限，并记录审计日志。
 * 
 * @author LingFrame
 */
public interface PermissionService {

    /**
     * 检查插件是否有某项权限。
     * 
     * @param pluginId 插件ID
     * @param capability 能力标识，例如 "datasource", "redis"
     * @param accessType 访问类型，如 READ, WRITE
     * @return 如果允许访问则返回 true，否则返回 false
     */
    boolean isAllowed(String pluginId, String capability, AccessType accessType);

    /**
     * 获取插件的完整权限配置。
     * <p>
     * 注意：此方法返回的权限配置可能包含敏感信息，应谨慎使用，通常只供 Core 内部或特定管理工具使用。
     * </p>
     * @param pluginId 插件ID
     * @param capability 能力标识
     * @return 权限配置对象，如果不存在则返回 null 或空对象
     */
    // TODO: 定义 Permission 配置对象，目前先返回 Object
    Object getPermission(String pluginId, String capability);

    /**
     * 记录审计日志。
     * 
     * @param pluginId 插件ID
     * @param capability 能力标识
     * @param operation 具体操作，例如 SQL 命令类型、Redis 方法名
     * @param allowed 是否允许该操作
     */
    void audit(String pluginId, String capability, String operation, boolean allowed);
}
