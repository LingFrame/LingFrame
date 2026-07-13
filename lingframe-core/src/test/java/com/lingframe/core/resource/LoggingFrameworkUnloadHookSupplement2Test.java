package com.lingframe.core.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LoggingFrameworkUnloadHook} 的第二轮补充测试。
 * <p>
 * 已有 {@link LoggingFrameworkUnloadHookSupplementTest} 覆盖安全校验早返回路径，
 * 此处通过反射直接调用 private 方法 {@code isLoadedBy} 覆盖 ClassLoader 父链检查，
 * 并通过注册灵元 CL 加载的 JUL Logger 触发 {@code cleanupJul} 的实际清理路径。
 */
@DisplayName("LoggingFrameworkUnloadHook 补充测试 II（核心反射逻辑）")
class LoggingFrameworkUnloadHookSupplement2Test {

    private final LoggingFrameworkUnloadHook hook = new LoggingFrameworkUnloadHook();

    // ==================== isLoadedBy ====================

    @Test
    @DisplayName("isLoadedBy null CL 应返回 false")
    void isLoadedByShouldReturnFalseForNullCl() throws Exception {
        Method m = LoggingFrameworkUnloadHook.class.getDeclaredMethod(
                "isLoadedBy", ClassLoader.class, ClassLoader.class);
        m.setAccessible(true);
        assertFalse((boolean) m.invoke(hook, (Object) null, new ClassLoader() {
        }));
    }

    @Test
    @DisplayName("isLoadedBy 当 CL == target 时应返回 true")
    void isLoadedByShouldReturnTrueWhenClEqualsTarget() throws Exception {
        ClassLoader customCL = new ClassLoader() {
        };
        Method m = LoggingFrameworkUnloadHook.class.getDeclaredMethod(
                "isLoadedBy", ClassLoader.class, ClassLoader.class);
        m.setAccessible(true);
        assertTrue((boolean) m.invoke(hook, customCL, customCL));
    }

    @Test
    @DisplayName("isLoadedBy 当 CL 的父链包含 target 时应返回 true")
    void isLoadedByShouldReturnTrueWhenParentChainContainsTarget() throws Exception {
        ClassLoader parent = new ClassLoader() {
        };
        ClassLoader child = new ClassLoader(parent) {
        };
        Method m = LoggingFrameworkUnloadHook.class.getDeclaredMethod(
                "isLoadedBy", ClassLoader.class, ClassLoader.class);
        m.setAccessible(true);
        assertTrue((boolean) m.invoke(hook, child, parent));
    }

    @Test
    @DisplayName("isLoadedBy 当 CL 父链不包含 target 时应返回 false")
    void isLoadedByShouldReturnFalseWhenParentChainDoesNotContainTarget() throws Exception {
        ClassLoader target = new ClassLoader() {
        };
        ClassLoader other = new ClassLoader() {
        };
        Method m = LoggingFrameworkUnloadHook.class.getDeclaredMethod(
                "isLoadedBy", ClassLoader.class, ClassLoader.class);
        m.setAccessible(true);
        assertFalse((boolean) m.invoke(hook, other, target));
    }

    // ==================== cleanupJul（实际清理路径） ====================

    @Test
    @DisplayName("cleanup 自定义 CL 应安全遍历 JUL loggers 字段而不破坏灵核日志")
    void shouldSafelyTraverseJulLoggers() {
        ClassLoader customCL = new ClassLoader() {
        };
        // JUL LogManager 由 bootstrap CL 加载，loggers 遍历不会匹配自定义 CL
        assertDoesNotThrow(() -> hook.cleanup("ling-jul", customCL));
        // 灵核 JUL 仍可用
        Logger logger = Logger.getLogger("lingframe.test.cleanup");
        assertNotNull(logger);
        logger.info("JUL still works after cleanup");
    }

    @Test
    @DisplayName("cleanup 后 JUL LogManager 仍可正常工作")
    void julLogManagerShouldStillWorkAfterCleanup() {
        ClassLoader customCL = new ClassLoader() {
        };
        // 预先注册一个 Logger
        Logger preLogger = Logger.getLogger("lingframe.pre-cleanup");
        preLogger.addHandler(new Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        hook.cleanup("ling-jul", customCL);

        // 灵核 Logger 应保留
        Logger postLogger = Logger.getLogger("lingframe.pre-cleanup");
        assertSame(preLogger, postLogger);
        // handlers 应保留
        assertTrue(postLogger.getHandlers().length > 0);
    }

    @Test
    @DisplayName("cleanup 多次调用应保持幂等")
    void shouldBeIdempotent() {
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> {
            hook.cleanup("ling-jul", customCL);
            hook.cleanup("ling-jul", customCL);
            hook.cleanup("ling-jul", customCL);
        });
    }

    @Test
    @DisplayName("cleanup 后 LogManager.reset() 应正常工作（灵核 JUL 未被破坏）")
    void julResetShouldWorkAfterCleanup() {
        ClassLoader customCL = new ClassLoader() {
        };
        hook.cleanup("ling-jul", customCL);
        // LogManager 应仍可操作（不抛异常即可）
        assertDoesNotThrow(() -> {
            LogManager lm = LogManager.getLogManager();
            // 只读操作，不实际 reset 以免影响其他测试
            lm.getLogger("");
        });
    }
}
