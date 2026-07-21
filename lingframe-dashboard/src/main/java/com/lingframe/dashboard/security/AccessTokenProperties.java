package com.lingframe.dashboard.security;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;

/**
 * 访问令牌配置属性
 * <p>
 * 生产默认强制鉴权（enabled=true），开发环境需显式配置 {@code lingframe.dashboard.access-token.enabled=false} 关闭。
 * <p>
 * ⚠️ 破坏性变更：默认 {@code enabled=true} 为 fail-closed 安全姿态。升级时必须显式配置
 * {@code lingframe.dashboard.access-token.token} 提供有效令牌，或显式设置
 * {@code lingframe.dashboard.access-token.enabled=false} 关闭鉴权，否则启动失败。
 */
@Slf4j
@Data
@ConfigurationProperties(prefix = "lingframe.dashboard.access-token")
public class AccessTokenProperties implements InitializingBean {

    /**
     * 是否启用令牌认证，默认 true（生产强制鉴权）。
     * <p>
     * 开发环境需在 application.yml 显式设置 enabled=false 才能关闭。
     */
    private boolean enabled = true;

    /**
     * 主访问令牌
     */
    private String token = "";

    /**
     * 备用令牌列表（支持多 token 并存，用于轮换过渡期）
     * 轮换流程：1. 添加新 token 到 secondaryTokens → 2. 部署新配置到客户端 → 3. 移除旧 token
     */
    private List<String> secondaryTokens = new ArrayList<>();

    /**
     * 是否允许弱口令启动。
     * <p>
     * 默认 true：本地示例可用 {@code 123456}。
     * 生产请显式设置 {@code lingframe.dashboard.access-token.allow-weak=false}，
     * 弱口令将导致启动失败（fail-closed）。
     */
    private boolean allowWeak = true;

    /**
     * 启动期校验：启用鉴权时 token 不能为空，否则启动失败（fail-closed）。
     * 当 {@code allowWeak=false} 时，弱口令同样 fail-closed。
     * <p>
     * 使用 {@link InitializingBean} 而非 {@code javax/jakarta.annotation.PostConstruct}，
     * 保证 SB2/SB3 共用同一 main 源码，不依赖注解包差异。
     */
    @Override
    public void afterPropertiesSet() {
        if (enabled) {
            Assert.hasText(token,
                    "lingframe.dashboard.access-token.token must be set when access-token.enabled=true (production). "
                            + "To disable authentication in development, set lingframe.dashboard.access-token.enabled=false explicitly.");
            if (isWeakToken(token)) {
                if (!allowWeak) {
                    throw new IllegalArgumentException(
                            "lingframe.dashboard.access-token.token is too weak (e.g. demo default '123456'). "
                                    + "Set a strong unique token, or set allow-weak=true only for local demos.");
                }
                // allowWeak=true：示例/本地可用，强制留下可观测警告
                log.warn("Dashboard access-token looks weak (e.g. demo default). "
                        + "Use a strong unique token before exposing the control plane. "
                        + "Set lingframe.dashboard.access-token.allow-weak=false to fail-closed on weak tokens.");
            }
        }
    }

    /**
     * 识别明显弱口令 / 示例默认值，仅用于启动告警。
     */
    static boolean isWeakToken(String value) {
        if (value == null) {
            return true;
        }
        String t = value.trim();
        if (t.length() < 8) {
            return true;
        }
        String lower = t.toLowerCase();
        return "123456".equals(lower)
                || "password".equals(lower)
                || "admin".equals(lower)
                || "token".equals(lower)
                || "changeme".equals(lower)
                || "lingframe".equals(lower);
    }

    /**
     * 获取所有有效 token（主 token + 备用 token）
     */
    public List<String> getAllValidTokens() {
        List<String> all = new ArrayList<>();
        if (token != null && !token.isEmpty()) {
            all.add(token);
        }
        if (secondaryTokens != null) {
            all.addAll(secondaryTokens);
        }
        return all;
    }

    /**
     * 验证 token 是否有效
     */
    public boolean isValidToken(String inputToken) {
        if (!enabled) {
            return true;
        }
        if (inputToken == null || inputToken.isEmpty()) {
            return false;
        }
        return getAllValidTokens().contains(inputToken);
    }
}
