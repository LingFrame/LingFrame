package com.lingframe.dashboard.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORS 配置属性默认值与读写测试
 */
@DisplayName("CORS 配置属性测试")
class CorsPropertiesTest {

    @Test
    @DisplayName("默认值：enabled=true / 空源列表 / 4 方法 / 3 头 / maxAge=3600")
    void shouldHaveCorrectDefaults() {
        CorsProperties p = new CorsProperties();

        assertTrue(p.isEnabled(), "默认应启用 CORS 过滤");
        assertTrue(p.getAllowedOrigins().isEmpty(), "默认源列表为空");
        assertEquals(Arrays.asList("GET", "POST", "DELETE", "OPTIONS"), p.getAllowedMethods());
        assertEquals(Arrays.asList("Content-Type", "X-Access-Token", "X-Requested-With"), p.getAllowedHeaders());
        assertEquals(3600L, p.getMaxAge());
    }

    @Test
    @DisplayName("setter 应正确回写属性")
    void shouldSetProperties() {
        CorsProperties p = new CorsProperties();
        p.setEnabled(false);
        List<String> origins = Arrays.asList("https://example.com", "https://app.test");
        p.setAllowedOrigins(origins);
        p.setMaxAge(7200);

        assertEquals(false, p.isEnabled());
        assertEquals(origins, p.getAllowedOrigins());
        assertEquals(7200L, p.getMaxAge());
    }

    @Test
    @DisplayName("allowedOrigins 默认应为可变列表（@Data 生成的 ArrayList）")
    void allowedOriginsDefaultShouldBeMutable() {
        CorsProperties p = new CorsProperties();
        p.getAllowedOrigins().add("https://x.com");
        assertEquals(1, p.getAllowedOrigins().size());
    }
}
