package com.lingframe.dashboard.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 数据库初始化器：建表 + 过期数据清理
 * <p>
 * 使用 {@link InitializingBean} 兼容 SB2/SB3，避免 javax/jakarta.annotation 差异。
 */
@Slf4j
@RequiredArgsConstructor
public class StorageInitializer implements InitializingBean {

    private final JdbcTemplate jdbcTemplate;
    private final StorageProperties properties;

    @Override
    public void afterPropertiesSet() {
        init();
    }

    public void init() {
        try {
            // 启用 WAL 模式（并发读写性能优化，内存数据库不支持则忽略）
            try {
                jdbcTemplate.execute("PRAGMA journal_mode=WAL");
                jdbcTemplate.execute("PRAGMA busy_timeout=5000");
            } catch (Exception ignored) {
                // 内存数据库不支持 WAL，忽略
            }

            createTables();
            cleanupExpiredData();

            log.info("SQLite storage initialization completed: {}", properties.getPath());
        } catch (Exception e) {
            throw new IllegalStateException("SQLite 存储初始化失败", e);
        }
    }

    private void createTables() {
        // 指标快照表
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS metrics_snapshot (" +
            "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  timestamp BIGINT NOT NULL," +
            "  cpu_usage DOUBLE," +
            "  process_cpu_load DOUBLE," +
            "  heap_used_mb BIGINT," +
            "  heap_max_mb BIGINT," +
            "  heap_usage DOUBLE," +
            "  metaspace_used_kb BIGINT," +
            "  metaspace_max_kb BIGINT," +
            "  metaspace_usage DOUBLE," +
            "  compressed_class_space_used_kb BIGINT," +
            "  loaded_class_count INT," +
            "  total_loaded_class_count BIGINT," +
            "  unloaded_class_count BIGINT," +
            "  thread_count INT," +
            "  daemon_thread_count INT," +
            "  peak_thread_count INT," +
            "  gc_count BIGINT," +
            "  gc_time_ms BIGINT," +
            "  memory_used_mb BIGINT," +
            "  memory_total_mb BIGINT," +
            "  memory_usage DOUBLE," +
            "  available_processors INT," +
            "  system_load_average DOUBLE" +
            ")"
        );
        jdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_metrics_timestamp ON metrics_snapshot(timestamp)"
        );

        // 治理配置表（复合主键：同一灵元可存储多种配置类型）
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS governance_config (" +
            "  ling_id VARCHAR(128) NOT NULL," +
            "  config_type VARCHAR(32) NOT NULL," +
            "  config_data TEXT NOT NULL," +
            "  updated_at BIGINT NOT NULL," +
            "  PRIMARY KEY (ling_id, config_type)" +
            ")"
        );

        // 灵元状态表
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS ling_status (" +
            "  ling_id VARCHAR(128) PRIMARY KEY," +
            "  status VARCHAR(32) NOT NULL," +
            "  version VARCHAR(64)," +
            "  updated_at BIGINT NOT NULL" +
            ")"
        );

        // 审计日志表
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS audit_log (" +
            "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  timestamp BIGINT NOT NULL," +
            "  ling_id VARCHAR(128)," +
            "  action VARCHAR(64) NOT NULL," +
            "  detail TEXT," +
            "  result VARCHAR(16)" +
            ")"
        );
        jdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_log(timestamp)"
        );

        // 幂等迁移：为已部署的旧库补新列（CREATE TABLE IF NOT EXISTS 不会给旧表加列）
        migrateMetricsSnapshotColumns();
    }

    /**
     * 幂等 ALTER TABLE：检查列是否存在，缺失则补列。
     * 兼容已部署的旧库（CREATE TABLE IF NOT EXISTS 不会给旧表加新列）。
     */
    private void migrateMetricsSnapshotColumns() {
        try {
            List<String> columns = jdbcTemplate.queryForList(
                    "SELECT name FROM pragma_table_info('metrics_snapshot')",
                    String.class
            );
            if (!columns.contains("compressed_class_space_used_kb")) {
                jdbcTemplate.execute(
                    "ALTER TABLE metrics_snapshot ADD COLUMN compressed_class_space_used_kb BIGINT"
                );
                log.info("Migrated metrics_snapshot: added compressed_class_space_used_kb column");
            }
        } catch (Exception e) {
            log.warn("metrics_snapshot column migration skipped: {}", e.getMessage());
        }
    }

    private void cleanupExpiredData() {
        long now = System.currentTimeMillis();

        // 清理过期指标数据
        int metricsDeleted = jdbcTemplate.update(
            "DELETE FROM metrics_snapshot WHERE timestamp < ?",
            now - (long) properties.getMetricsRetentionDays() * 24 * 3600 * 1000
        );
        if (metricsDeleted > 0) {
            log.info("Cleaned up expired metrics data: {} records", metricsDeleted);
        }

        // 清理过期审计日志
        int auditDeleted = jdbcTemplate.update(
            "DELETE FROM audit_log WHERE timestamp < ?",
            now - (long) properties.getAuditRetentionDays() * 24 * 3600 * 1000
        );
        if (auditDeleted > 0) {
            log.info("Cleaned up expired audit logs: {} records", auditDeleted);
        }
    }
}
