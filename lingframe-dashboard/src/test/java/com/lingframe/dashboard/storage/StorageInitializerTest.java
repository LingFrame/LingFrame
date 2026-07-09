package com.lingframe.dashboard.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * StorageInitializer 测试
 * <p>
 * 覆盖 init 建表流程、cleanupExpiredData 过期数据清理、WAL 模式异常容错、
 * 以及 createTables 失败时抛 IllegalStateException 的路径。
 */
@DisplayName("StorageInitializer 测试")
class StorageInitializerTest {

    private JdbcTemplate jdbcTemplate;
    private StorageProperties properties;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite::memory:");
        ds.setSuppressClose(true);
        jdbcTemplate = new JdbcTemplate(ds);
        properties = new StorageProperties();
    }

    // ==================== init 正常路径 ====================

    @Nested
    @DisplayName("init 正常路径")
    class InitTests {

        @Test
        @DisplayName("应成功建表并清理过期数据")
        void shouldCreateTablesAndCleanup() {
            StorageInitializer initializer = new StorageInitializer(jdbcTemplate, properties);

            assertDoesNotThrow(() -> initializer.init());

            // 验证表已创建：插入并查询应成功
            jdbcTemplate.update("INSERT INTO metrics_snapshot(timestamp) VALUES(?)", System.currentTimeMillis());
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM metrics_snapshot", Integer.class);
        }

        @Test
        @DisplayName("重复 init 不应抛异常（IF NOT EXISTS）")
        void shouldNotThrowOnRepeatedInit() {
            StorageInitializer initializer = new StorageInitializer(jdbcTemplate, properties);
            initializer.init();

            assertDoesNotThrow(() -> initializer.init());
        }
    }

    // ==================== cleanupExpiredData ====================

    @Nested
    @DisplayName("过期数据清理")
    class CleanupTests {

        @Test
        @DisplayName("应删除过期的 metrics_snapshot 记录")
        void shouldDeleteExpiredMetrics() {
            // 先 init 建表
            new StorageInitializer(jdbcTemplate, properties).init();
            // 插入一条过期记录（30 天前）
            long expiredTime = System.currentTimeMillis() - 31L * 24 * 3600 * 1000;
            jdbcTemplate.update("INSERT INTO metrics_snapshot(timestamp) VALUES(?)", expiredTime);

            // 再次 init 触发 cleanupExpiredData
            new StorageInitializer(jdbcTemplate, properties).init();

            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM metrics_snapshot", Integer.class);
            assertEquals(0, count.intValue());
        }

        @Test
        @DisplayName("应删除过期的 audit_log 记录")
        void shouldDeleteExpiredAuditLogs() {
            new StorageInitializer(jdbcTemplate, properties).init();
            long expiredTime = System.currentTimeMillis() - 31L * 24 * 3600 * 1000;
            jdbcTemplate.update("INSERT INTO audit_log(timestamp, action) VALUES(?, ?)", expiredTime, "test");

            new StorageInitializer(jdbcTemplate, properties).init();

            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class);
            assertEquals(0, count.intValue());
        }

        @Test
        @DisplayName("不应删除未过期的记录")
        void shouldNotDeleteRecentRecords() {
            new StorageInitializer(jdbcTemplate, properties).init();
            long recentTime = System.currentTimeMillis();
            jdbcTemplate.update("INSERT INTO metrics_snapshot(timestamp) VALUES(?)", recentTime);

            new StorageInitializer(jdbcTemplate, properties).init();

            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM metrics_snapshot", Integer.class);
            assertEquals(1, count.intValue());
        }
    }

    // ==================== WAL 模式异常容错 ====================

    @Nested
    @DisplayName("WAL 模式异常容错")
    class WalModeTests {

        @Test
        @DisplayName("PRAGMA journal_mode=WAL 失败时应被忽略不影响 init")
        void shouldIgnoreWalModeFailure() {
            // 内存数据库不支持 WAL，PRAGMA 会失败但被忽略
            StorageInitializer initializer = new StorageInitializer(jdbcTemplate, properties);

            assertDoesNotThrow(() -> initializer.init());
        }
    }

    // ==================== createTables 失败 ====================

    @Nested
    @DisplayName("createTables 失败")
    class CreateTablesFailureTests {

        @Test
        @DisplayName("execute 抛异常时应包装为 IllegalStateException")
        void shouldThrowIllegalStateWhenExecuteFails() {
            JdbcTemplate badJdbcTemplate = mock(JdbcTemplate.class);
            doThrow(new RuntimeException("connection error"))
                    .when(badJdbcTemplate).execute(anyString());
            StorageInitializer initializer = new StorageInitializer(badJdbcTemplate, properties);

            // PRAGMA WAL 失败被忽略，但 createTables 的 CREATE TABLE 失败应抛 IllegalStateException
            assertThrows(IllegalStateException.class, () -> initializer.init());
        }
    }

    // ==================== 自定义保留天数 ====================

    @Nested
    @DisplayName("自定义保留天数")
    class CustomRetentionTests {

        @Test
        @DisplayName("应按配置的 metricsRetentionDays 清理数据")
        void shouldCleanupByCustomRetentionDays() {
            properties.setMetricsRetentionDays(7);
            properties.setAuditRetentionDays(7);

            new StorageInitializer(jdbcTemplate, properties).init();
            // 8 天前的记录应被清理
            long eightDaysAgo = System.currentTimeMillis() - 8L * 24 * 3600 * 1000;
            jdbcTemplate.update("INSERT INTO metrics_snapshot(timestamp) VALUES(?)", eightDaysAgo);

            new StorageInitializer(jdbcTemplate, properties).init();

            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM metrics_snapshot", Integer.class);
            assertEquals(0, count.intValue());
        }
    }

    private static void assertEquals(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
