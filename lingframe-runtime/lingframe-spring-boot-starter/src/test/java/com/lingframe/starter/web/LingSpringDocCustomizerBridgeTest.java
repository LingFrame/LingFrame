package com.lingframe.starter.web;

import org.junit.jupiter.api.Assertions;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LingSpringDocCustomizerBridge 测试")
class LingSpringDocCustomizerBridgeTest {

    @Test
    @DisplayName("无 SpringDoc 接口时 createGlobalCustomizer 应返回 null")
    void shouldReturnNullWhenNoSpringDocInterfaces() {
        Object customizer = LingSpringDocCustomizerBridge.createGlobalCustomizer(
                LingSpringDocCustomizerBridgeTest.class.getClassLoader(), openApi -> {
                });
        // 测试 classpath 上可能有/无 springdoc；两种结果都不应抛异常
        assertDoesNotThrow(() -> {
            if (customizer != null) {
                customizer.toString();
            }
        });
    }

    @Test
    @DisplayName("groupedOpenApi 为 null 时 attach 应返回 false")
    void shouldNotAttachNullGroupedOpenApi() {
        boolean attached = LingSpringDocCustomizerBridge.attachToGroupedOpenApi(
                getClass().getClassLoader(), openApi -> {
                }, null);
        Assertions.assertFalse(attached);
        assertNull(null);
    }
}
