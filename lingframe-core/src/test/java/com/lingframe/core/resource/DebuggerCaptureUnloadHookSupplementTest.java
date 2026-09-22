package com.lingframe.core.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link DebuggerCaptureUnloadHook} 的补充测试。
 * <p>
 * 该钩子用于清理 IntelliJ IDEA 调试器 CaptureStorage 中持有灵元 ClassLoader 的异常缓存。
 * 非调试模式下 CaptureStorage 类不存在，钩子应优雅返回。
 */
@DisplayName("DebuggerCaptureUnloadHook 补充测试")
class DebuggerCaptureUnloadHookSupplementTest {

    private final DebuggerCaptureUnloadHook hook = new DebuggerCaptureUnloadHook();

    @Test
    @DisplayName("cleanup null ClassLoader 应直接返回而不报错")
    void shouldReturnEarlyForNullClassLoader() {
        assertDoesNotThrow(() -> hook.cleanup("ling-debug", null));
    }

    @Test
    @DisplayName("cleanup 自定义 ClassLoader 不报错（非调试模式，CaptureStorage 类不存在）")
    void shouldCleanupCustomClassLoaderWithoutDebuggerAgent() {
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> hook.cleanup("ling-debug", customCL));
    }

    @Test
    @DisplayName("cleanup 系统 ClassLoader 不报错")
    void shouldCleanupSystemClassLoader() {
        assertDoesNotThrow(() -> hook.cleanup("ling-debug", ClassLoader.getSystemClassLoader()));
    }

    @Test
    @DisplayName("cleanup 同一 ClassLoader 多次调用不报错（幂等）")
    void shouldCleanupMultipleTimes() {
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> {
            hook.cleanup("ling-debug", customCL);
            hook.cleanup("ling-debug", customCL);
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
