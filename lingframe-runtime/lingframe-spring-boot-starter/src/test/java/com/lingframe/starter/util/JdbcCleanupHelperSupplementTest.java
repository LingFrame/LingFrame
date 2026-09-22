package com.lingframe.starter.util;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JdbcCleanupHelper} 补充测试。
 * <p>
 * 该类的 unregisterAll() 静态方法负责注销所有已注册的 JDBC 驱动，
 * 用于在灵元卸载时清理 DriverManager 中残留的子容器驱动引用。
 * 由于该方法是全局副作用操作，用例在执行前后会保存并恢复原驱动集合，避免污染其他测试。
 */
@DisplayName("JdbcCleanupHelper 补充测试")
class JdbcCleanupHelperSupplementTest {

    /**
     * 简易测试驱动：仅满足 DriverManager 注册/注销所需的最小契约。
     * acceptsURL 返回 false 避免干扰其他用例。
     */
    private static class FakeDriver implements Driver {
        @Override
        public boolean acceptsURL(String url) {
            return false;
        }

        @Override
        public Connection connect(String url, Properties info) {
            return null;
        }

        @Override
        public int getMajorVersion() {
            return 0;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }
    }

    @Test
    @DisplayName("unregisterAll 应注销所有已注册的 JDBC 驱动并返回注销数量")
    void shouldUnregisterAllRegisteredDrivers() throws SQLException {
        // 保存现有驱动，测试结束后恢复，避免污染全局状态
        List<Driver> snapshot = snapshotDrivers();
        FakeDriver driver1 = new FakeDriver();
        FakeDriver driver2 = new FakeDriver();
        DriverManager.registerDriver(driver1);
        DriverManager.registerDriver(driver2);
        try {
            assertTrue(isDriverRegistered(driver1));
            assertTrue(isDriverRegistered(driver2));

            int removed = JdbcCleanupHelper.unregisterAll();

            // 至少注销了本测试注册的两个驱动
            assertTrue(removed >= 2);
            assertFalse(isDriverRegistered(driver1));
            assertFalse(isDriverRegistered(driver2));
        } finally {
            restoreDrivers(snapshot);
        }
    }

    @Test
    @DisplayName("unregisterAll 在没有驱动时也应安全执行并返回 0")
    void shouldReturnZeroWhenNoDriversLeft() throws SQLException {
        // 保存并清空所有驱动，确保第二次调用返回 0
        List<Driver> snapshot = snapshotDrivers();
        try {
            JdbcCleanupHelper.unregisterAll();
            // 此时驱动集合已空，再次调用应返回 0
            int removed = JdbcCleanupHelper.unregisterAll();
            assertEquals(0, removed);
        } finally {
            restoreDrivers(snapshot);
        }
    }

    @Test
    @DisplayName("unregisterAll 应能持续遍历并清空 getDrivers 返回的全部驱动")
    void shouldClearAllDriversReturnedByGetDrivers() throws SQLException {
        List<Driver> snapshot = snapshotDrivers();
        FakeDriver driver = new FakeDriver();
        DriverManager.registerDriver(driver);
        try {
            JdbcCleanupHelper.unregisterAll();
            // 清理后 getDrivers 应不再返回任何驱动
            assertEquals(0, countRegisteredDrivers());
        } finally {
            restoreDrivers(snapshot);
        }
    }

    private static List<Driver> snapshotDrivers() {
        List<Driver> drivers = new ArrayList<>();
        Enumeration<Driver> en = DriverManager.getDrivers();
        while (en.hasMoreElements()) {
            drivers.add(en.nextElement());
        }
        return drivers;
    }

    private static int countRegisteredDrivers() {
        int count = 0;
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            drivers.nextElement();
            count++;
        }
        return count;
    }

    private static boolean isDriverRegistered(Driver target) {
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            if (drivers.nextElement() == target) {
                return true;
            }
        }
        return false;
    }

    private static void restoreDrivers(List<Driver> snapshot) {
        // 先清空当前可能残留的驱动
        try {
            Enumeration<Driver> en = DriverManager.getDrivers();
            while (en.hasMoreElements()) {
                DriverManager.deregisterDriver(en.nextElement());
            }
        } catch (SQLException ignored) {
            // 忽略注销失败
        }
        // 恢复原始驱动
        for (Driver d : snapshot) {
            try {
                DriverManager.registerDriver(d);
            } catch (SQLException ignored) {
                // 忽略重复注册
            }
        }
    }
}
