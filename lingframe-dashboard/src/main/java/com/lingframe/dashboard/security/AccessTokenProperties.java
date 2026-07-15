package com.lingframe.dashboard.security;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

import javax.annotation.PostConstruct;
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
public class AccessTokenProperties {

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
     * 启动期校验：启用鉴权时 token 不能为空，否则启动失败（fail-closed）。
     */
    @PostConstruct
    void validate() {
        if (enabled) {
            Assert.hasText(token,
                    "lingframe.dashboard.access-token.token must be set when access-token.enabled=true (production). "
                            + "To disable authentication in development, set lingframe.dashboard.access-token.enabled=false explicitly.");
        }
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
