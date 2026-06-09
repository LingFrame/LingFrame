package com.lingframe.core.resource;

import com.lingframe.core.event.EventBus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BasicResourceGuard 测试")
class BasicResourceGuardTest {

    @Test
    @DisplayName("cleanup null ClassLoader 不报错")
    void shouldHandleNullClassLoader() {
        BasicResourceGuard guard = new BasicResourceGuard();
        assertDoesNotThrow(() -> guard.cleanup("ling-a", null));
    }

    @Test
    @DisplayName("cleanup 系统 ClassLoader 不报错")
    void shouldSkipSystemClassLoader() {
        BasicResourceGuard guard = new BasicResourceGuard();
        assertDoesNotThrow(() -> guard.cleanup("ling-a", ClassLoader.getSystemClassLoader()));
    }

    @Test
    @DisplayName("cleanup 扩展 ClassLoader 不报错")
    void shouldSkipPlatformClassLoader() {
        BasicResourceGuard guard = new BasicResourceGuard();
        ClassLoader platformCL = ClassLoader.getSystemClassLoader().getParent();
        assertDoesNotThrow(() -> guard.cleanup("ling-a", platformCL));
    }

    @Test
    @DisplayName("cleanup 自定义 ClassLoader 不报错")
    void shouldCleanupCustomClassLoader() {
        BasicResourceGuard guard = new BasicResourceGuard();
        ClassLoader customCL = new ClassLoader() {};
        assertDoesNotThrow(() -> guard.cleanup("ling-a", customCL));
    }

    @Test
    @DisplayName("shutdown 不报错")
    void shouldShutdownCleanly() {
        BasicResourceGuard guard = new BasicResourceGuard();
        assertDoesNotThrow(() -> guard.shutdown());
    }

    @Test
    @DisplayName("getCapabilitySnapshot 返回非空")
    void shouldReturnCapabilitySnapshot() {
        BasicResourceGuard guard = new BasicResourceGuard();
        assertNotNull(guard.getCapabilitySnapshot());
        assertTrue(guard.getCapabilitySnapshot().getJdkVersion() > 0);
    }

    @Test
    @DisplayName("带 EventBus 的构造器不报错")
    void shouldCreateWithEventBus() {
        EventBus eventBus = new EventBus();
        assertDoesNotThrow(() -> {
            BasicResourceGuard guard = new BasicResourceGuard(eventBus);
            guard.cleanup("ling-a", new ClassLoader() {});
        });
    }

    @Test
    @DisplayName("CapabilitySnapshot getter 正常工作")
    void shouldAccessCapabilitySnapshotFields() {
        BasicResourceGuard guard = new BasicResourceGuard();
        BasicResourceGuard.CapabilitySnapshot snapshot = guard.getCapabilitySnapshot();

        assertTrue(snapshot.getJdkVersion() > 0);
        // toSummary 不报错
        assertNotNull(snapshot.toSummary());
        assertTrue(snapshot.toSummary().contains("jdk="));
    }

    @Test
    @DisplayName("publishCapabilitySnapshot 不报错")
    void shouldPublishCapabilitySnapshot() {
        EventBus eventBus = new EventBus();
        BasicResourceGuard guard = new BasicResourceGuard(eventBus);
        assertDoesNotThrow(() -> guard.publishCapabilitySnapshot("test-runtime"));
    }

    @Test
    @DisplayName("无 EventBus 时 publishCapabilitySnapshot 不报错")
    void shouldPublishWithoutEventBus() {
        BasicResourceGuard guard = new BasicResourceGuard();
        assertDoesNotThrow(() -> guard.publishCapabilitySnapshot("test-runtime"));
    }

    @Test
    @DisplayName("cleanup 同一 ClassLoader 多次不报错")
    void shouldCleanupMultipleTimes() {
        BasicResourceGuard guard = new BasicResourceGuard();
        ClassLoader customCL = new ClassLoader() {};
        assertDoesNotThrow(() -> {
            guard.cleanup("ling-a", customCL);
            guard.cleanup("ling-a", customCL);
        });
    }

    @Test
    @DisplayName("cleanup 不同 lingId 的同一 ClassLoader 不报错")
    void shouldCleanupSameClassLoaderWithDifferentLingId() {
        BasicResourceGuard guard = new BasicResourceGuard();
        ClassLoader customCL = new ClassLoader() {};
        assertDoesNotThrow(() -> {
            guard.cleanup("ling-a", customCL);
            guard.cleanup("ling-b", customCL);
        });
    }

    @Test
    @DisplayName("CapabilitySnapshot 各 getter 不报错")
    void shouldAccessAllCapabilitySnapshotGetters() {
        BasicResourceGuard guard = new BasicResourceGuard();
        BasicResourceGuard.CapabilitySnapshot snapshot = guard.getCapabilitySnapshot();

        // 所有 getter 都不抛异常
        assertDoesNotThrow(() -> {
            snapshot.getJdkVersion();
            snapshot.isThreadTargetAccessible();
            snapshot.isThreadAccessControlAccessible();
            snapshot.isAccessControlContextAccessible();
            snapshot.isVirtualThreadIntrospectionAvailable();
            snapshot.isDriverManagerAccessible();
            snapshot.toSummary();
        });
    }

    @Test
    @DisplayName("cleanup 注册了 ShutdownHook 的 ClassLoader 不报错")
    void shouldCleanupClassLoaderWithShutdownHook() {
        BasicResourceGuard guard = new BasicResourceGuard();
        ClassLoader customCL = new ClassLoader() {};
        Thread hook = new Thread(() -> {});
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            assertDoesNotThrow(() -> guard.cleanup("ling-hook", customCL));
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(hook); } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("cleanup 线程持有自定义 ClassLoader 的 ThreadLocal 不报错")
    void shouldCleanupThreadLocalWithCustomClassLoader() throws Exception {
        BasicResourceGuard guard = new BasicResourceGuard();
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
            assertDoesNotThrow(() -> guard.cleanup("ling-tl", customCL));
        } finally {
            done.countDown();
            t.join(5000);
        }
    }

    @Test
    @DisplayName("cleanup 线程 contextClassLoader 为目标 ClassLoader 不报错")
    void shouldCleanupThreadContextClassLoader() throws Exception {
        BasicResourceGuard guard = new BasicResourceGuard();
        ClassLoader customCL = new ClassLoader() {};

        Thread t = new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        t.setContextClassLoader(customCL);
        t.start();

        try {
            assertDoesNotThrow(() -> guard.cleanup("ling-ctx", customCL));
        } finally {
            t.interrupt();
            t.join(5000);
        }
    }

    @Test
    @DisplayName("cleanup 空 lingId 不报错")
    void shouldCleanupWithEmptyLingId() {
        BasicResourceGuard guard = new BasicResourceGuard();
        ClassLoader customCL = new ClassLoader() {};
        assertDoesNotThrow(() -> guard.cleanup("", customCL));
    }

    @Test
    @DisplayName("shutdown 后 cleanup 不报错")
    void shouldCleanupAfterShutdown() {
        BasicResourceGuard guard = new BasicResourceGuard();
        guard.shutdown();
        ClassLoader customCL = new ClassLoader() {};
        assertDoesNotThrow(() -> guard.cleanup("ling-after-shutdown", customCL));
    }

    @Test
    @DisplayName("CapabilitySnapshot toSummary 包含关键信息")
    void shouldContainKeyInfoInSummary() {
        BasicResourceGuard guard = new BasicResourceGuard();
        BasicResourceGuard.CapabilitySnapshot snapshot = guard.getCapabilitySnapshot();
        String summary = snapshot.toSummary();

        assertTrue(summary.contains("jdk="));
        assertTrue(summary.contains("target="));
        assertTrue(summary.contains("driverManager="));
    }
}
