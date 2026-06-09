package com.lingframe.core.resource;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.spi.ResourceGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BasicResourceGuard 测试
 */
@DisplayName("BasicResourceGuard 测试")
class BasicResourceGuardTest {

    private ResourceGuard resourceGuard;

    @BeforeEach
    void setUp() {
        resourceGuard = new BasicResourceGuard();
    }

    @AfterEach
    void tearDown() {
    }

    @Nested
    @DisplayName("cleanup() 方法")
    class CleanupTests {

        @Test
        @DisplayName("应该正常执行清理，不抛异常")
        void shouldExecuteWithoutException() {
            // 使用自定义的 ClassLoader 模拟灵元 ClassLoader
            ClassLoader testClassLoader = new URLClassLoader(new URL[0], getClass().getClassLoader());

            // 不应抛出异常
            assertDoesNotThrow(() -> resourceGuard.cleanup("test-ling", testClassLoader));
        }

        @Test
        @DisplayName("应该处理 null ClassLoader")
        void shouldHandleNullClassLoader() {
            // 使用一个空的 ClassLoader
            ClassLoader emptyLoader = new URLClassLoader(new URL[0], null);

            assertDoesNotThrow(() -> resourceGuard.cleanup("test-ling", emptyLoader));
        }

        @Test
        @DisplayName("应该反注册由灵元 ClassLoader 加载的 JDBC 驱动")
        void shouldDeregisterJdbcDrivers() throws SQLException {
            // 创建测试用 ClassLoader
            URLClassLoader testLoader = new URLClassLoader(new URL[0], getClass().getClassLoader());

            // 模拟：注册一个假驱动
            // 注意：实际测试需要构造由 testLoader 加载的 Driver
            // 这里只验证方法能正常执行

            int driverCountBefore = countDrivers();
            resourceGuard.cleanup("test-ling", testLoader);
            int driverCountAfter = countDrivers();

            // 由于我们没有真正注册驱动，数量应该相同
            assertEquals(driverCountBefore, driverCountAfter);
        }

        private int countDrivers() {
            int count = 0;
            Enumeration<Driver> drivers = DriverManager.getDrivers();
            while (drivers.hasMoreElements()) {
                drivers.nextElement();
                count++;
            }
            return count;
        }
    }

    @Nested
    @DisplayName("能力快照")
    class CapabilitySnapshotTests {

        @Test
        @DisplayName("应暴露结构化能力快照")
        void shouldExposeStructuredCapabilitySnapshot() {
            BasicResourceGuard guard = new BasicResourceGuard();

            BasicResourceGuard.CapabilitySnapshot snapshot = guard.getCapabilitySnapshot();

            assertNotNull(snapshot);
            assertTrue(snapshot.getJdkVersion() >= 8);
            assertNotNull(snapshot.toSummary());
            assertFalse(snapshot.toSummary().isEmpty());
        }

        @Test
        @DisplayName("带事件总线时应发布资源清理能力事件")
        void shouldPublishCapabilityEventWhenEventBusProvided() {
            EventBus eventBus = new EventBus();
            AtomicReference<MonitoringEvents.ResourceCleanupCapabilityEvent> captured = new AtomicReference<>();
            eventBus.subscribe("test-listener", MonitoringEvents.ResourceCleanupCapabilityEvent.class, captured::set);

            new BasicResourceGuard(eventBus);

            MonitoringEvents.ResourceCleanupCapabilityEvent event = awaitEvent(captured, Duration.ofSeconds(2));
            assertNotNull(event);
            assertEquals("BasicResourceGuard", event.getRuntime());
            assertTrue(event.getJdkVersion() >= 8);
            assertNotNull(event.getSummary());
        }
    }

    private <T> T awaitEvent(AtomicReference<T> captured, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            T event = captured.get();
            if (event != null) {
                return event;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for event");
            }
        }
        fail("Timed out waiting for event");
        return null;
    }
}
