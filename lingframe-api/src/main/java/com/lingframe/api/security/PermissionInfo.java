package com.lingframe.api.security;

import java.io.Serializable;
import java.time.Instant;
import java.util.Optional;

/**
 * 权限信息记录
 * <p>
 * 描述一个插件获得的某项能力权限的详细信息。
 * 用于替代 PermissionService.getPermission() 的 Object 返回类型。
 * </p>
 *
 * @author LingFrame
 */
public record PermissionInfo(
        /**
         * 插件 ID
         */
        String pluginId,

        /**
         * 能力标识，如 "storage:sql", "cache:redis"
         */
        String capability,

        /**
         * 授予的访问类型
         */
        AccessType accessType,

        /**
         * 权限授予时间
         */
        Instant grantedAt,

        /**
         * 权限过期时间（可选）
         * null 表示永不过期
         */
        Instant expiresAt,

        /**
         * 权限来源描述
         * 如 "plugin.yml", "runtime-grant", "admin-console"
         */
        String source) implements Serializable {

    /**
     * 创建一个永不过期的权限信息
     */
    public static PermissionInfo permanent(String pluginId, String capability, AccessType accessType, String source) {
        return new PermissionInfo(pluginId, capability, accessType, Instant.now(), null, source);
    }

    /**
     * 创建一个有过期时间的权限信息
     */
    public static PermissionInfo withExpiry(String pluginId, String capability, AccessType accessType,
            Instant expiresAt, String source) {
        return new PermissionInfo(pluginId, capability, accessType, Instant.now(), expiresAt, source);
    }

    /**
     * 判断权限是否已过期
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * 判断权限是否满足所需的访问类型
     *
     * @param required 所需的访问类型
     * @return 如果当前权限满足要求返回 true
     */
    public boolean satisfies(AccessType required) {
        return !isExpired() && accessType.satisfies(required);
    }

    /**
     * 获取过期时间（Optional 形式）
     */
    public Optional<Instant> getExpiresAt() {
        return Optional.ofNullable(expiresAt);
    }
}
