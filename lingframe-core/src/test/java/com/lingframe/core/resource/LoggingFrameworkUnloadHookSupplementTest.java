package com.lingframe.core.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link LoggingFrameworkUnloadHook} 的补充测试。
 * <p>
 * 该钩子清理 logback/log4j2/jul/slf4j 中关联灵元 ClassLoader 的引用。
 * 由于日志框架通常由父 CL 加载，安全判定会跳过 shutdown，
 * 测试主要验证不破坏宿主日志输出。
 */
@DisplayName("LoggingFrameworkUnloadHook 补充测试")
class LoggingFrameworkUnloadHookSupplementTest {

    private final LoggingFrameworkUnloadHook hook = new LoggingFrameworkUnloadHook();

    @Test
    @DisplayName("cleanup null ClassLoader 应被安全校验拦截而不报错")
    void shouldSkipNullClassLoader() {
        assertDoesNotThrow(() -> hook.cleanup("ling-log", null));
    }

    @Test
    @DisplayName("cleanup 系统 ClassLoader 应被安全校验拦截而不报错")
    void shouldSkipSystemClassLoader() {
        assertDoesNotThrow(() -> hook.cleanup("ling-log", ClassLoader.getSystemClassLoader()));
    }

    @Test
    @DisplayName("cleanup 扩展/平台 ClassLoader 应被安全校验拦截而不报错")
    void shouldSkipPlatformClassLoader() {
        ClassLoader platform = ClassLoader.getSystemClassLoader().getParent();
        assertDoesNotThrow(() -> hook.cleanup("ling-log", platform));
    }

    @Test
    @DisplayName("cleanup 自定义 ClassLoader 不报错（日志框架由父 CL 加载，应全部跳过）")
    void shouldCleanupCustomClassLoader() {
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> hook.cleanup("ling-log", customCL));
    }

    @Test
    @DisplayName("cleanup 同一 ClassLoader 多次调用不报错（幂等）")
    void shouldCleanupMultipleTimes() {
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> {
            hook.cleanup("ling-log", customCL);
            hook.cleanup("ling-log", customCL);
        });
    }

    @Test
    @DisplayName("cleanup 空 lingId 不报错")
    void shouldCleanupWithEmptyLingId() {
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> hook.cleanup("", customCL));
    }

    @Test
    @DisplayName("cleanup null lingId 不报错")
    void shouldCleanupWithNullLingId() {
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> hook.cleanup(null, customCL));
    }
}
