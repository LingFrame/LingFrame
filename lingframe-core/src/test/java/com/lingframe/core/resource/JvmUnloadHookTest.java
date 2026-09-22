package com.lingframe.core.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JVM 卸载钩子测试")
class JvmUnloadHookTest {

    @Nested
    @DisplayName("ThreadReferenceUnloadHook 测试")
    class ThreadReferenceHookTest {

        private final ThreadReferenceUnloadHook hook = new ThreadReferenceUnloadHook();

        @Test
        @DisplayName("cleanup null ClassLoader 不报错")
        void shouldHandleNullClassLoader() {
            assertDoesNotThrow(() -> hook.cleanup("ling-a", null));
        }

        @Test
        @DisplayName("cleanup 系统 ClassLoader 不报错")
        void shouldSkipSystemClassLoader() {
            assertDoesNotThrow(() -> hook.cleanup("ling-a", ClassLoader.getSystemClassLoader()));
        }

        @Test
        @DisplayName("cleanup 扩展 ClassLoader 不报错")
        void shouldSkipPlatformClassLoader() {
            ClassLoader platformCL = ClassLoader.getSystemClassLoader().getParent();
            assertDoesNotThrow(() -> hook.cleanup("ling-a", platformCL));
        }

        @Test
        @DisplayName("cleanup 自定义 ClassLoader 不报错")
        void shouldCleanupCustomClassLoader() {
            ClassLoader customCL = new ClassLoader() {};
            assertDoesNotThrow(() -> hook.cleanup("ling-a", customCL));
        }

        @Test
        @DisplayName("cleanup 同一 ClassLoader 多次不报错")
        void shouldCleanupMultipleTimes() {
            ClassLoader customCL = new ClassLoader() {};
            assertDoesNotThrow(() -> {
                hook.cleanup("ling-a", customCL);
                hook.cleanup("ling-a", customCL);
            });
        }

        @Test
        @DisplayName("cleanup 线程持有自定义 ClassLoader 的 ThreadLocal 不报错")
        void shouldCleanupThreadLocalWithCustomClassLoader() throws Exception {
            ClassLoader customCL = new ClassLoader() {};
            ThreadLocal<Object> tl = new ThreadLocal<>();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);

            Thread t = new Thread(() -> {
                tl.set("test-value");
                started.countDown();
                try { done.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            t.setContextClassLoader(customCL);
            t.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));

            try {
                assertDoesNotThrow(() -> hook.cleanup("ling-tl", customCL));
            } finally {
                done.countDown();
                t.join(5000);
            }
        }

        @Test
        @DisplayName("cleanup 线程 contextClassLoader 为目标 ClassLoader 不报错")
        void shouldCleanupThreadContextClassLoader() throws Exception {
            ClassLoader customCL = new ClassLoader() {};
            Thread t = new Thread(() -> {
                try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            t.setContextClassLoader(customCL);
            t.start();

            try {
                assertDoesNotThrow(() -> hook.cleanup("ling-ctx", customCL));
            } finally {
                t.interrupt();
                t.join(5000);
            }
        }

        @Test
        @DisplayName("cleanup 空 lingId 不报错")
        void shouldCleanupWithEmptyLingId() {
            ClassLoader customCL = new ClassLoader() {};
            assertDoesNotThrow(() -> hook.cleanup("", customCL));
        }
    }

    @Nested
    @DisplayName("JdbcDriverUnloadHook 测试")
    class JdbcDriverHookTest {

        private final JdbcDriverUnloadHook hook = new JdbcDriverUnloadHook();

        @Test
        @DisplayName("cleanup null ClassLoader 不报错")
        void shouldHandleNullClassLoader() {
            assertDoesNotThrow(() -> hook.cleanup("ling-a", null));
        }

        @Test
        @DisplayName("cleanup 系统 ClassLoader 不报错")
        void shouldSkipSystemClassLoader() {
            assertDoesNotThrow(() -> hook.cleanup("ling-a", ClassLoader.getSystemClassLoader()));
        }

        @Test
        @DisplayName("cleanup 自定义 ClassLoader 不报错")
        void shouldCleanupCustomClassLoader() {
            ClassLoader customCL = new ClassLoader() {};
            assertDoesNotThrow(() -> hook.cleanup("ling-a", customCL));
        }
    }

    @Nested
    @DisplayName("JvmShutdownHookUnloadHook 测试")
    class ShutdownHookTest {

        private final JvmShutdownHookUnloadHook hook = new JvmShutdownHookUnloadHook();

        @Test
        @DisplayName("cleanup null ClassLoader 不报错")
        void shouldHandleNullClassLoader() {
            assertDoesNotThrow(() -> hook.cleanup("ling-a", null));
        }

        @Test
        @DisplayName("cleanup 系统 ClassLoader 不报错")
        void shouldSkipSystemClassLoader() {
            assertDoesNotThrow(() -> hook.cleanup("ling-a", ClassLoader.getSystemClassLoader()));
        }

        @Test
        @DisplayName("cleanup 注册了 ShutdownHook 的 ClassLoader 不报错")
        void shouldCleanupClassLoaderWithShutdownHook() {
            ClassLoader customCL = new ClassLoader() {};
            Thread hookThread = new Thread(() -> {});
            try {
                Runtime.getRuntime().addShutdownHook(hookThread);
                assertDoesNotThrow(() -> hook.cleanup("ling-hook", customCL));
            } finally {
                try { Runtime.getRuntime().removeShutdownHook(hookThread); } catch (Exception ignored) {}
            }
        }
    }

    @Nested
    @DisplayName("JvmCleanupSupport 测试")
    class SupportTest {

        @Test
        @DisplayName("CapabilitySnapshot 各 getter 正常工作")
        void shouldAccessAllCapabilitySnapshotGetters() {
            JvmCleanupSupport.CapabilitySnapshot snapshot = JvmCleanupSupport.CAPABILITY_SNAPSHOT;
            assertTrue(snapshot.getJdkVersion() > 0);
            assertNotNull(snapshot.toSummary());
            assertTrue(snapshot.toSummary().contains("jdk="));
            assertTrue(snapshot.toSummary().contains("target="));
            assertTrue(snapshot.toSummary().contains("driverManager="));
        }

        @Test
        @DisplayName("isSafeToCleanup 对 null ClassLoader 返回 false")
        void shouldRejectNullClassLoader() {
            assertFalse(JvmCleanupSupport.isSafeToCleanup("ling", null));
        }

        @Test
        @DisplayName("isSafeToCleanup 对系统 ClassLoader 返回 false")
        void shouldRejectSystemClassLoader() {
            assertFalse(JvmCleanupSupport.isSafeToCleanup("ling", ClassLoader.getSystemClassLoader()));
        }

        @Test
        @DisplayName("isSafeToCleanup 对自定义 ClassLoader 返回 true")
        void shouldAcceptCustomClassLoader() {
            assertTrue(JvmCleanupSupport.isSafeToCleanup("ling", new ClassLoader() {}));
        }
    }
}
