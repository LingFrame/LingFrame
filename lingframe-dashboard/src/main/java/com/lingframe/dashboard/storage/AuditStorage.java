package com.lingframe.dashboard.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * 审计日志存储
 */
@Slf4j
@RequiredArgsConstructor
public class AuditStorage {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 记录审计日志
     */
    public void saveAuditLog(String lingId, String action, String detail, String result) {
        jdbcTemplate.update(
            "INSERT INTO audit_log (timestamp, ling_id, action, detail, result) VALUES (?, ?, ?, ?, ?)",
            System.currentTimeMillis(), lingId, action, detail, result
        );
    }

    /**
     * 查询审计日志
     */
    public List<Map<String, Object>> queryAuditLogs(String lingId, Long start, Long end, int limit) {
        long now = System.currentTimeMillis();
        if (end == null || end <= 0) {
            end = now;
        }
        if (start == null || start <= 0) {
            start = end - 3600_000L; // 默认最近 1 小时
        }
        if (limit <= 0) {
            limit = 100;
        }

        if (lingId != null && !lingId.isEmpty()) {
            return jdbcTemplate.queryForList(
                "SELECT * FROM audit_log WHERE ling_id = ? AND timestamp BETWEEN ? AND ? ORDER BY timestamp DESC LIMIT ?",
                lingId, start, end, limit
            );
        }
        return jdbcTemplate.queryForList(
            "SELECT * FROM audit_log WHERE timestamp BETWEEN ? AND ? ORDER BY timestamp DESC LIMIT ?",
            start, end, limit
        );
    }

    /**
     * 清理过期审计日志
     */
    public int cleanupBefore(long timestamp) {
        return jdbcTemplate.update(
            "DELETE FROM audit_log WHERE timestamp < ?", timestamp
        );
    }
}
