package com.lingframe.api.security;

import java.util.Map;

/**
 * 灵核提供的权限查询服务
 * 负责检查灵元是否有某项权限，并记录审计日志。
 *
 * @author LingFrame
 */
public interface PermissionService {

    /**
     * 检查灵元是否有某项权限。
     * 
     * @param lingId     灵元ID
     * @param capability 能力标识，例如 "datasource", "redis"
     * @param accessType 访问类型，如 READ, WRITE
     * @return 如果允许访问则返回 true，否则返回 false
     */
    boolean isAllowed(String lingId, String capability, AccessType accessType);

    void grant(String lingId, String capability, AccessType accessType);

    /**
     * 撤销灵元的某项权限
     *
     * @param lingId     灵元ID
     * @param capability 能力标识
     */
    default void revoke(String lingId, String capability) {
        // 默认空实现
    }

    /**
     * 获取灵元的权限信息。
     * <p>
     * 返回指定灵元对某项能力的权限详情，包括访问类型、授予时间、过期时间等。
     * </p>
     *
     * @param lingId     灵元ID
     * @param capability 能力标识
     * @return 权限信息，如果不存在则返回 null
     */
    PermissionInfo getPermission(String lingId, String capability);

    /**
     * 记录审计日志。
     * 
     * @param lingId     灵元ID
     * @param capability 能力标识
     * @param operation  具体操作，例如 SQL 命令类型、Redis 方法名
     * @param allowed    是否允许该操作
     */
    void audit(String lingId, String capability, String operation, boolean allowed);

    /**
     * 记录结构化审计事件。
     */
    void audit(PermissionAuditRecord record);

    default void removeLing(String lingId) {
    }

    /**
     * 原子替换灵元的全部权限。
     * <p>
     * 用于治理规则同步场景，避免「先清空再逐条 grant」造成的权限真空窗口：
     * 旧实现中 removeLing 与 grant 之间存在时间窗口，期间该灵元的所有请求会被拒绝。
     * <p>
     * 默认实现直接抛出 {@link UnsupportedOperationException}，强制实现方显式选择原子或非原子策略，
     * 避免静默回退到 removeLing + grant 的不安全路径。实现方必须覆写本方法以提供原子性保证。
     *
     * @param lingId      灵元ID
     * @param permissions 新的权限映射（capability -> accessType），为 null 或空等价于清空
     */
    default void replacePermissions(String lingId, Map<String, AccessType> permissions) {
        throw new UnsupportedOperationException(
                "replacePermissions must be implemented by the provider to ensure atomic permission replacement");
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

    /**
     * 检查灵元是否配置了指定前缀的 capability 规则。
     * <p>
     * 用于判断灵元是否"显式启用"了某类细粒度治理。例如 SQL 表级治理：
     * 若灵元配置了任何 {@code storage:sql:table:*} 规则，则视为启用了表级白名单模式，
     * 所有表操作都必须有显式表级权限；若未配置任何表级规则，则只看 generic 总开关。
     * </p>
     * <p>
     * 默认返回 false（未启用细粒度治理），实现方应覆写以查询实际权限配置。
     * </p>
     *
     * @param lingId           灵元ID
     * @param capabilityPrefix 能力标识前缀，例如 "storage:sql:table:"
     * @return 灵元是否配置了匹配前缀的权限规则
     */
    default boolean hasCapabilityPrefix(String lingId, String capabilityPrefix) {
        return false;
    }
}
