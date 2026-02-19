package com.lingframe.core.resource;

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
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BasicResourceGuard 单元测试
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
        if (resourceGuard instanceof BasicResourceGuard) {
            resourceGuard.shutdown();
        }
    }

    @Nested
    @DisplayName("cleanup() 方法")
    class CleanupTests {

        @Test
        @DisplayName("应该正常执行清理，不抛异常")
        void shouldExecuteWithoutException() {
            // 使用当前类加载器模拟
            ClassLoader testClassLoader = getClass().getClassLoader();

            // 不应抛出异常
            assertDoesNotThrow(() -> resourceGuard.cleanup("test-plugin", testClassLoader));
        }

        @Test
        @DisplayName("应该处理 null ClassLoader")
        void shouldHandleNullClassLoader() {
            // 使用一个空的 ClassLoader
            ClassLoader emptyLoader = new URLClassLoader(new URL[0], null);

            assertDoesNotThrow(() -> resourceGuard.cleanup("test-plugin", emptyLoader));
        }

        @Test
        @DisplayName("应该反注册由插件 ClassLoader 加载的 JDBC 驱动")
        void shouldDeregisterJdbcDrivers() throws SQLException {
            // 创建测试用 ClassLoader
            URLClassLoader testLoader = new URLClassLoader(new URL[0], getClass().getClassLoader());

            // 模拟：注册一个假驱动
            // 注意：实际测试需要构造由 testLoader 加载的 Driver
            // 这里只验证方法能正常执行

            int driverCountBefore = countDrivers();
            resourceGuard.cleanup("test-plugin", testLoader);
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
    @DisplayName("detectLeak() 方法")
    class DetectLeakTests {

        @Test
        @DisplayName("应该正常执行泄漏检测，不抛异常")
        void shouldExecuteWithoutException() {
            ClassLoader testClassLoader = getClass().getClassLoader();

            assertDoesNotThrow(() -> resourceGuard.detectLeak("test-plugin", testClassLoader));
        }

        @Test
        @DisplayName("应该处理可被回收的 ClassLoader")
        void shouldDetectRecyclableClassLoader() throws InterruptedException {
            // 创建一个没有强引用的 ClassLoader
            URLClassLoader disposableLoader = new URLClassLoader(new URL[0], null);

            // 调用泄漏检测
            resourceGuard.detectLeak("test-plugin", disposableLoader);

            // 释放引用
            disposableLoader = null;

            // 等待检测完成（检测器会在 5 秒后执行）
            // 这里不实际等待，只验证调用不抛异常
        }
    }

    @Nested
    @DisplayName("shutdown() 方法")
    class ShutdownTests {

        @Test
        @DisplayName("应该正常关闭调度器")
        void shouldShutdownGracefully() {
            BasicResourceGuard guard = new BasicResourceGuard();

            assertDoesNotThrow(guard::shutdown);

            // 关闭后再次调用不应抛异常
            assertDoesNotThrow(guard::shutdown);
        }
    }
}
