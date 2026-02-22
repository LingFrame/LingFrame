package com.lingframe.api.security;

/**
 * Core 提供 - 权限查询服务
 * 负责检查单元是否有某项权限，并记录审计日志。
 * 
 * @author LingFrame
 */
public interface PermissionService {

    /**
     * 检查单元是否有某项权限。
     * 
     * @param lingId   单元ID
     * @param capability 能力标识，例如 "datasource", "redis"
     * @param accessType 访问类型，如 READ, WRITE
     * @return 如果允许访问则返回 true，否则返回 false
     */
    boolean isAllowed(String lingId, String capability, AccessType accessType);

    void grant(String lingId, String capability, AccessType accessType);

    /**
     * 撤销单元的某项权限
     *
     * @param lingId   单元ID
     * @param capability 能力标识
     */
    default void revoke(String lingId, String capability) {
        // 默认空实现
    }

    /**
     * 获取单元的权限信息。
     * <p>
     * 返回指定单元对某项能力的权限详情，包括访问类型、授予时间、过期时间等。
     * </p>
     *
     * @param lingId   单元ID
     * @param capability 能力标识
     * @return 权限信息，如果不存在则返回 null
     */
    PermissionInfo getPermission(String lingId, String capability);

    /**
     * 记录审计日志。
     * 
     * @param lingId   单元ID
     * @param capability 能力标识
     * @param operation  具体操作，例如 SQL 命令类型、Redis 方法名
     * @param allowed    是否允许该操作
     */
    void audit(String lingId, String capability, String operation, boolean allowed);

    default void removeLing(String lingId) {
    }

    /**
     * 检查灵核治理是否启用。
     * <p>
     * 当返回 true 时，所有没有 LingContext 的操作（灵核操作）也需要受到治理。
     * 当返回 false（默认）时，灵核操作默认放行。
     * </p>
     * 
     * @return 是否启用灵核治理
     */
    default boolean isLingCoreGovernanceEnabled() {
        return false; // 默认不启用
    }
}
