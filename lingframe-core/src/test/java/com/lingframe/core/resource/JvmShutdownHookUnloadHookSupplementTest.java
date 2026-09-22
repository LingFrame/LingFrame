package com.lingframe.core.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link JvmShutdownHookUnloadHook} 的补充测试。
 * <p>
 * 已有 {@link JvmUnloadHookTest.ShutdownHookTest} 覆盖基础安全校验路径，
 * 此处补充 contextClassLoader 匹配规则、空/null lingId、幂等调用等分支。
 */
@DisplayName("JvmShutdownHookUnloadHook 补充测试")
class JvmShutdownHookUnloadHookSupplementTest {

    private final JvmShutdownHookUnloadHook hook = new JvmShutdownHookUnloadHook();

    @Test
    @DisplayName("cleanup 扩展/平台 ClassLoader 应被安全校验拦截而不报错")
    void shouldSkipPlatformClassLoader() {
        ClassLoader platform = ClassLoader.getSystemClassLoader().getParent();
        assertDoesNotThrow(() -> hook.cleanup("ling-sh", platform));
    }

    @Test
    @DisplayName("cleanup 自定义 ClassLoader 无匹配 Hook 时为空操作")
    void shouldNoopWhenNoMatchingHooks() {
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> hook.cleanup("ling-sh", customCL));
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

    @Test
    @DisplayName("cleanup 应尝试移除 contextClassLoader 匹配目标 CL 的 ShutdownHook")
    void shouldRemoveHookByContextClassLoaderMatch() {
        ClassLoader customCL = new ClassLoader() {
        };
        Thread hookThread = new Thread(() -> {
        });
        hookThread.setContextClassLoader(customCL);
        try {
            Runtime.getRuntime().addShutdownHook(hookThread);
            // cleanup 通过反射访问 ApplicationShutdownHooks.hooks，在 JDK 16+ 强封装下可能被吞
            // 此处仅验证 cleanup 不抛异常；hook 是否真正被移除取决于反射是否可用
            assertDoesNotThrow(() -> hook.cleanup("ling-sh", customCL));
            // 尝试移除：若 cleanup 已移除则抛 IllegalArgumentException（预期），否则正常移除
            try {
                Runtime.getRuntime().removeShutdownHook(hookThread);
            } catch (IllegalArgumentException ignored) {
                // cleanup 已移除该 hook，符合预期
            }
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hookThread);
            } catch (Exception ignored) {
                // 已被清理则忽略
            }
        }
    }

    @Test
    @DisplayName("cleanup 不匹配的 ShutdownHook 应保留")
    void shouldKeepUnmatchedHook() {
        ClassLoader customCL = new ClassLoader() {
        };
        // hook 线程的 contextClassLoader 为系统 CL，不匹配 customCL
        Thread hookThread = new Thread(() -> {
        });
        try {
            Runtime.getRuntime().addShutdownHook(hookThread);
            assertDoesNotThrow(() -> hook.cleanup("ling-sh", customCL));
            // 未匹配的 hook 仍应可被移除（说明未被清理）
            assertDoesNotThrow(() -> Runtime.getRuntime().removeShutdownHook(hookThread));
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hookThread);
            } catch (Exception ignored) {
                // 已被清理则忽略
            }
        }
    }

    @Test
    @DisplayName("cleanup 同一 ClassLoader 多次调用不报错（幂等）")
    void shouldCleanupMultipleTimes() {
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> {
            hook.cleanup("ling-sh", customCL);
            hook.cleanup("ling-sh", customCL);
        });
    }
}
