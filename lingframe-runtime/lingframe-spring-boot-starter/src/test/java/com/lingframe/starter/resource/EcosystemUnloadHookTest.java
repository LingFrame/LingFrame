package com.lingframe.starter.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("生态卸载钩子测试")
class EcosystemUnloadHookTest {

    @Nested
    @DisplayName("SpringEcosystemUnloadHook 测试")
    class EcosystemHookTest {

        private final SpringEcosystemUnloadHook hook = new SpringEcosystemUnloadHook();

        @Test
        @DisplayName("cleanup null ClassLoader 不报错")
        void shouldHandleNullClassLoader() {
            assertDoesNotThrow(() -> hook.cleanup("ling-a", null));
        }

        @Test
        @DisplayName("cleanup 自定义 ClassLoader 不报错")
        void shouldCleanupCustomClassLoader() {
            ClassLoader customCL = new ClassLoader() {};
            assertDoesNotThrow(() -> hook.cleanup("ling-a", customCL));
        }

        @Test
        @DisplayName("preCleanup 无 Context 不报错")
        void shouldPreCleanupWithoutContext() {
            assertDoesNotThrow(() -> hook.preCleanup("ling-a", null, null));
        }

        /**
         * 直接驱动 {@code safeCleanup} 私有方法验证隔离契约。
         * <p>
         * 选择直接反射调用而非 mock final Cleaner 类，原因：
         * 1. {@link SpringStaticCacheCleaner} / {@link SpringShutdownHookCleaner} 等均为
         *    包级私有 {@code final class}，Mockito 默认 mock maker 无法 mock；
         * 2. 单步失败隔离契约实际由 {@code safeCleanup} 的 try-catch(Throwable) 实现，
         *    与具体 Cleaner 类型无关——直接测试该方法即可覆盖契约本质；
         * 3. 避免引入 mockito-inline 全局配置影响其他测试，也避免反射改 final 字段
         *    在不同 JDK 上的兼容性风险。
         */
        private void invokeSafeCleanup(String lingId, String stepName, Runnable action) throws Exception {
            Method method = SpringEcosystemUnloadHook.class.getDeclaredMethod(
                    "safeCleanup", String.class, String.class, Runnable.class);
            method.setAccessible(true);
            method.invoke(hook, lingId, stepName, action);
        }

        @Test
        @DisplayName("某步骤抛 RuntimeException 时后续步骤仍应被调用（单步失败隔离）")
        void shouldContinueWithRemainingStepsWhenOneFails() throws Exception {
            // 第一个 Runnable 抛异常，模拟 Cleaner 内部失败
            Runnable failingStep = mock(Runnable.class);
            doThrow(new RuntimeException("simulated cleaner failure"))
                    .when(failingStep).run();
            // 第二个 Runnable 正常执行，验证后续步骤不被跳过
            Runnable normalStep = mock(Runnable.class);

            // 两次调用模拟 cleanup() 中的相邻步骤
            invokeSafeCleanup("ling-a", "step-1", failingStep);
            invokeSafeCleanup("ling-a", "step-2", normalStep);

            // 失败步骤确实被调用
            verify(failingStep, times(1)).run();
            // 关键断言：后续步骤仍被调用——证明单步失败不阻塞后续步骤
            verify(normalStep, times(1)).run();
        }

        @Test
        @DisplayName("某步骤抛 Error 时也应被隔离，不阻塞后续步骤")
        void shouldIsolateErrorAndContinueWithRemainingSteps() throws Exception {
            // Error 步骤——模拟 JVM 级错误（如 StackOverflowError / OOM）
            Runnable errorStep = mock(Runnable.class);
            doThrow(new StackOverflowError("simulated JVM error"))
                    .when(errorStep).run();
            Runnable normalStep = mock(Runnable.class);

            // safeCleanup 捕获 Throwable（含 Error），不传播
            invokeSafeCleanup("ling-a", "step-1", errorStep);
            invokeSafeCleanup("ling-a", "step-2", normalStep);

            verify(errorStep, times(1)).run();
            // Error 不应阻塞后续步骤
            verify(normalStep, times(1)).run();
        }
    }

    @Nested
    @DisplayName("StorageCacheUnloadHook 测试")
    class StorageHookTest {

        private final StorageCacheUnloadHook hook = new StorageCacheUnloadHook();

        @Test
        @DisplayName("cleanup 自定义 ClassLoader 不报错")
        void shouldCleanupCustomClassLoader() {
            ClassLoader customCL = new ClassLoader() {};
            assertDoesNotThrow(() -> hook.cleanup("ling-a", customCL));
        }
    }
}
