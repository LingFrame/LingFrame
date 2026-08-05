package com.lingframe.dashboard.storage;

import com.lingframe.core.metrics.JVMMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标数据存储：写入快照 + 历史聚合查询
 */
@Slf4j
@RequiredArgsConstructor
public class MetricsStorage {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 写入一条指标快照
     */
    public void saveSnapshot(JVMMetrics m) {
        jdbcTemplate.update(
            "INSERT INTO metrics_snapshot (" +
            "  timestamp, cpu_usage, process_cpu_load, heap_used_mb, heap_max_mb, heap_usage," +
            "  metaspace_used_kb, metaspace_max_kb, metaspace_usage," +
            "  loaded_class_count, total_loaded_class_count, unloaded_class_count," +
            "  thread_count, daemon_thread_count, peak_thread_count," +
            "  gc_count, gc_time_ms, memory_used_mb, memory_total_mb, memory_usage," +
            "  available_processors, system_load_average" +
            ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            m.getTimestamp(), m.getCpuUsage(), m.getProcessCpuLoad(),
            m.getHeapUsedMB(), m.getHeapMaxMB(), m.getHeapUsagePercent(),
            m.getMetaspaceUsedKB(), m.getMetaspaceMaxKB(), m.getMetaspaceUsagePercent(),
            m.getLoadedClassCount(), m.getTotalLoadedClassCount(), m.getUnloadedClassCount(),
            m.getThreadCount(), m.getDaemonThreadCount(), m.getPeakThreadCount(),
            m.getGcCount(), m.getGcTimeMs(),
            m.getUsedMemoryMB(), m.getTotalMemoryMB(), m.getMemoryUsagePercent(),
            m.getAvailableProcessors(), m.getSystemLoadAverage()
        );
    }

    /**
     * 查询历史指标数据，支持时间范围和自动降采样
     *
     * @param start    起始时间戳（毫秒），默认 1 小时前
     * @param end      结束时间戳（毫秒），默认当前时间
     * @param interval 聚合间隔（秒），0 表示不聚合（返回原始数据）
     * @return 指标数据列表
     */
    public List<Map<String, Object>> queryHistory(Long start, Long end, int interval) {
        long now = System.currentTimeMillis();
        if (end == null || end <= 0) {
            end = now;
        }
        if (start == null || start <= 0) {
            start = end - 3600_000L; // 默认最近 1 小时
        }

        if (interval <= 0) {
            // 不聚合，返回原始数据点
            return jdbcTemplate.queryForList(
                "SELECT * FROM metrics_snapshot WHERE timestamp BETWEEN ? AND ? ORDER BY timestamp",
                start, end
            );
        }

        // 按时间窗口聚合
        long intervalMs = (long) interval * 1000;
        return jdbcTemplate.queryForList(
            "SELECT " +
            "  (timestamp / ?) * ? AS bucket," +
            "  AVG(cpu_usage) AS cpu_usage," +
            "  AVG(process_cpu_load) AS process_cpu_load," +
            "  AVG(heap_used_mb) AS heap_used_mb," +
            "  AVG(heap_max_mb) AS heap_max_mb," +
            "  AVG(heap_usage) AS heap_usage," +
            "  AVG(metaspace_used_kb) AS metaspace_used_kb," +
            "  AVG(metaspace_max_kb) AS metaspace_max_kb," +
            "  AVG(metaspace_usage) AS metaspace_usage," +
            "  AVG(loaded_class_count) AS loaded_class_count," +
            "  AVG(total_loaded_class_count) AS total_loaded_class_count," +
            "  AVG(unloaded_class_count) AS unloaded_class_count," +
            "  AVG(thread_count) AS thread_count," +
            "  AVG(daemon_thread_count) AS daemon_thread_count," +
            "  AVG(peak_thread_count) AS peak_thread_count," +
            "  MAX(gc_count) - MIN(gc_count) AS delta_gc_count," +
            "  MAX(gc_time_ms) - MIN(gc_time_ms) AS delta_gc_time_ms," +
            "  AVG(memory_used_mb) AS memory_used_mb," +
            "  AVG(memory_total_mb) AS memory_total_mb," +
            "  AVG(memory_usage) AS memory_usage," +
            "  AVG(available_processors) AS available_processors," +
            "  AVG(system_load_average) AS system_load_average " +
            "FROM metrics_snapshot " +
            "WHERE timestamp BETWEEN ? AND ? " +
            "GROUP BY bucket " +
            "ORDER BY bucket",
            intervalMs, intervalMs, start, end
        );
    }

    /**
     * 清理过期指标数据
     */
    public int cleanupBefore(long timestamp) {
        return jdbcTemplate.update(
            "DELETE FROM metrics_snapshot WHERE timestamp < ?", timestamp
        );
    }
}
