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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 插件 ID
     */
    private String pluginId;

    /**
     * 能力标识，如 "storage:sql", "cache:redis"
     */
    private String capability;

    /**
     * 授予的访问类型
     */
    private AccessType accessType;

    /**
     * 权限授予时间
     */
    @With
    private Instant grantedAt;

    /**
     * 权限过期时间（可选）
     * null 表示永不过期
     */
    @With
    private Instant expiresAt;

    /**
     * 权限来源描述
     * 如 "plugin.yml", "runtime-grant", "admin-console"
     */
    private String source;

    /**
     * 创建一个永不过期的权限信息
     */
    public static PermissionInfo permanent(String pluginId, String capability, AccessType accessType, String source) {
        return PermissionInfo.builder()
                .pluginId(pluginId)
                .capability(capability)
                .accessType(accessType)
                .grantedAt(Instant.now())
                .source(source)
                .build();
    }

    /**
     * 创建一个有过期时间的权限信息
     */
    public static PermissionInfo withExpiry(String pluginId, String capability, AccessType accessType,
                                            Instant expiresAt, String source) {
        return PermissionInfo.builder()
                .pluginId(pluginId)
                .capability(capability)
                .accessType(accessType)
                .grantedAt(Instant.now())
                .expiresAt(expiresAt)
                .source(source)
                .build();
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
