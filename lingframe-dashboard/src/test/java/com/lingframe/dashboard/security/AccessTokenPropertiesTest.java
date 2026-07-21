package com.lingframe.dashboard.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AccessTokenProperties.afterPropertiesSet() 启动期校验测试
 * <p>
 * 覆盖三条路径：
 * <ul>
 *   <li>enabled=true 且 token 空 → 抛 IllegalArgumentException（fail-closed）</li>
 *   <li>enabled=false → 放行（无需 token）</li>
 *   <li>enabled=true 且 token 非空 → 放行</li>
 * </ul>
 */
@DisplayName("AccessTokenProperties 校验测试")
class AccessTokenPropertiesTest {

    @Test
    @DisplayName("enabled=true 且 token 为空 → 抛出 IllegalArgumentException")
    void shouldFailWhenEnabledAndTokenBlank() {
        AccessTokenProperties p = new AccessTokenProperties();
        p.setEnabled(true);
        p.setToken("");

        assertThrows(IllegalArgumentException.class, p::afterPropertiesSet);
    }

    @Test
    @DisplayName("enabled=false → 放行（无需 token）")
    void shouldPassWhenDisabled() {
        AccessTokenProperties p = new AccessTokenProperties();
        p.setEnabled(false);
        p.setToken("");

        assertDoesNotThrow(p::afterPropertiesSet);
    }

    @Test
    @DisplayName("enabled=true 且 token 非空 → 放行")
    void shouldPassWhenEnabledAndTokenPresent() {
        AccessTokenProperties p = new AccessTokenProperties();
        p.setEnabled(true);
        p.setToken("secret-token");

        assertDoesNotThrow(p::afterPropertiesSet);
    }

    @Test
    @DisplayName("弱口令默认 allow-weak=true 时仍允许启动，仅告警不阻断")
    void weakTokenDoesNotFailStartupWhenAllowWeak() {
        AccessTokenProperties p = new AccessTokenProperties();
        p.setEnabled(true);
        p.setToken("123456");
        p.setAllowWeak(true);

        assertDoesNotThrow(p::afterPropertiesSet);
        assertTrue(AccessTokenProperties.isWeakToken("123456"));
        assertTrue(AccessTokenProperties.isWeakToken("admin"));
        assertFalse(AccessTokenProperties.isWeakToken("a-strong-unique-token-9f3"));
    }

    @Test
    @DisplayName("allow-weak=false 时弱口令 fail-closed 拒绝启动")
    void weakTokenFailsWhenAllowWeakFalse() {
        AccessTokenProperties p = new AccessTokenProperties();
        p.setEnabled(true);
        p.setToken("123456");
        p.setAllowWeak(false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, p::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("too weak"));
    }

    @Test
    @DisplayName("allow-weak=false 且强 token 时正常启动")
    void strongTokenPassesWhenAllowWeakFalse() {
        AccessTokenProperties p = new AccessTokenProperties();
        p.setEnabled(true);
        p.setToken("a-strong-unique-token-9f3");
        p.setAllowWeak(false);

        assertDoesNotThrow(p::afterPropertiesSet);
    }
}
