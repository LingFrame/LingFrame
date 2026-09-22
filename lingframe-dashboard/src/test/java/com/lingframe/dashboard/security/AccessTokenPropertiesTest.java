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

    @Test
    @DisplayName("isValidToken 合法/非法/空 token 三态正确")
    void isValidTokenShouldAcceptValidAndRejectInvalid() {
        AccessTokenProperties p = new AccessTokenProperties();
        p.setEnabled(true);
        p.setToken("main-secret-token");
        p.getSecondaryTokens().add("rotation-token-1");
        p.getSecondaryTokens().add("rotation-token-2");

        assertTrue(p.isValidToken("main-secret-token"));
        assertTrue(p.isValidToken("rotation-token-1"));
        assertTrue(p.isValidToken("rotation-token-2"));
        assertFalse(p.isValidToken("wrong-token"));
        assertFalse(p.isValidToken(null));
        assertFalse(p.isValidToken(""));
    }

    @Test
    @DisplayName("isValidToken 恒时比较不因前缀相同而提前通过")
    void isValidTokenShouldNotMatchByPrefix() {
        AccessTokenProperties p = new AccessTokenProperties();
        p.setEnabled(true);
        p.setToken("main-secret-token-abcdef");

        assertFalse(p.isValidToken("main-secret-token-abcdeX"), "同长度最后一位不同不应通过");
        assertFalse(p.isValidToken("main-secret-token-abcde"), "前缀相同但更短不应通过");
        assertTrue(p.isValidToken("main-secret-token-abcdef"));
    }

    @Test
    @DisplayName("disabled 时 isValidToken 恒放行")
    void isValidTokenShouldPassWhenDisabled() {
        AccessTokenProperties p = new AccessTokenProperties();
        p.setEnabled(false);
        p.setToken("");

        assertTrue(p.isValidToken("anything"));
        assertTrue(p.isValidToken(null));
    }
}
