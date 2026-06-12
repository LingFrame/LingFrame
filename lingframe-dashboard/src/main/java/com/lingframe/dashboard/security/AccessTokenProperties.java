package com.lingframe.dashboard.security;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 访问令牌配置属性
 * 支持热更新：通过 /actuator/env 或配置文件变更可实时生效
 */
@Slf4j
@Data
@ConfigurationProperties(prefix = "lingframe.dashboard.access-token")
public class AccessTokenProperties {

    /**
     * 是否启用令牌认证
     */
    private boolean enabled = false;

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
