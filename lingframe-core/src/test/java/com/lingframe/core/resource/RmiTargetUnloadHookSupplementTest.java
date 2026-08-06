package com.lingframe.core.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link RmiTargetUnloadHook} 的补充测试。
 * <p>
 * 该钩子通过反射清理 sun.rmi.transport.ObjectTable 中关联目标 ClassLoader 的 Target 条目，
 * 涉及大量反射操作 JDK 内部 API，故多数断言以「不抛异常」为准。
 */
@DisplayName("RmiTargetUnloadHook 补充测试")
class RmiTargetUnloadHookSupplementTest {

    private final RmiTargetUnloadHook hook = new RmiTargetUnloadHook();

    @Test
    @DisplayName("cleanup null ClassLoader 应被安全校验拦截而不报错")
    void shouldSkipNullClassLoader() {
        assertDoesNotThrow(() -> hook.cleanup("ling-rmi", null));
    }

    @Test
    @DisplayName("cleanup 系统 ClassLoader 应被安全校验拦截而不报错")
    void shouldSkipSystemClassLoader() {
        assertDoesNotThrow(() -> hook.cleanup("ling-rmi", ClassLoader.getSystemClassLoader()));
    }

    @Test
    @DisplayName("cleanup 扩展/平台 ClassLoader 应被安全校验拦截而不报错")
    void shouldSkipPlatformClassLoader() {
        ClassLoader platform = ClassLoader.getSystemClassLoader().getParent();
        assertDoesNotThrow(() -> hook.cleanup("ling-rmi", platform));
    }

    @Test
    @DisplayName("cleanup 自定义 ClassLoader 不报错（反射 ObjectTable 路径）")
    void shouldCleanupCustomClassLoader() {
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> hook.cleanup("ling-rmi", customCL));
    }

    @Test
    @DisplayName("cleanup 同一 ClassLoader 多次调用不报错（幂等）")
    void shouldCleanupMultipleTimes() {
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> {
            hook.cleanup("ling-rmi", customCL);
            hook.cleanup("ling-rmi", customCL);
            hook.cleanup("ling-rmi", customCL);
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
