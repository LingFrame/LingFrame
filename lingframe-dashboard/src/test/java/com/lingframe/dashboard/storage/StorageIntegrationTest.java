package com.lingframe.dashboard.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * SQLite 存储层集成测试
 */
class StorageIntegrationTest {

    private JdbcTemplate jdbcTemplate;
    private StorageInitializer initializer;
    private MetricsStorage metricsStorage;
    private GovernanceStorage governanceStorage;
    private AuditStorage auditStorage;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite::memory:");
        ds.setSuppressClose(true);
        jdbcTemplate = new JdbcTemplate(ds);

        StorageProperties props = new StorageProperties();
        initializer = new StorageInitializer(jdbcTemplate, props);
        initializer.init();

        metricsStorage = new MetricsStorage(jdbcTemplate);
        governanceStorage = new GovernanceStorage(jdbcTemplate, new ObjectMapper());
        auditStorage = new AuditStorage(jdbcTemplate);
    }

    @Test
    void tablesCreated_success() {
        // 验证 metrics_snapshot 表存在
        List<Map<String, Object>> result = jdbcTemplate.queryForList(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='metrics_snapshot'"
        );
        assertFalse(result.isEmpty(), "metrics_snapshot 表应存在");

        // 验证 governance_config 表存在
        result = jdbcTemplate.queryForList(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='governance_config'"
        );
        assertFalse(result.isEmpty(), "governance_config 表应存在");
    }

    @Test
    void governanceConfig_compositePrimaryKey_preventsOverwrite() {
        // 同一 lingId 不同 config_type 应共存
        governanceStorage.saveMigrationConfig("ling1", "{\"percent\":50}");
        governanceStorage.saveInvocationConfig("ling1", "{\"timeout\":1000}");

        Map<String, Map<String, String>> configs = governanceStorage.loadAllConfigs();
        assertTrue(configs.containsKey("ling1"));
        assertEquals(2, configs.get("ling1").size());
    }

    @Test
    void governanceConfig_upsert_updatesExisting() {
        governanceStorage.saveMigrationConfig("ling1", "{\"percent\":50}");
        governanceStorage.saveMigrationConfig("ling1", "{\"percent\":80}");

        String config = governanceStorage.loadMigrationConfig("ling1");
        assertTrue(config.contains("80"));
    }

    @Test
    void concurrentWrites_busyTimeout_doesNotThrow() throws Exception {
        int threads = 5;
        CountDownLatch latch = new CountDownLatch(threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    governanceStorage.saveMigrationConfig("ling-cc" + idx, "{\"p\":" + idx + "}");
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "并发写入应在超时内完成");
        executor.shutdown();
    }
}
