package com.lingframe.dashboard.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AccessTokenProperties.validate() 启动期校验测试
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

        assertThrows(IllegalArgumentException.class, p::validate);
    }

    @Test
    @DisplayName("enabled=false → 放行（无需 token）")
    void shouldPassWhenDisabled() {
        AccessTokenProperties p = new AccessTokenProperties();
        p.setEnabled(false);
        p.setToken("");

        assertDoesNotThrow(p::validate);
    }

    @Test
    @DisplayName("enabled=true 且 token 非空 → 放行")
    void shouldPassWhenEnabledAndTokenPresent() {
        AccessTokenProperties p = new AccessTokenProperties();
        p.setEnabled(true);
        p.setToken("secret-token");

        assertDoesNotThrow(p::validate);
    }
}
