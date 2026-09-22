package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.AuditLogDTO;
import com.lingframe.dashboard.storage.AuditStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Dashboard 安全审计查询控制器。
 */
@Slf4j
@RestController
@RequestMapping("/lingframe/dashboard/audit")
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class AuditController {

    private final AuditStorage auditStorage;

    @Autowired
    public AuditController(@Autowired(required = false) AuditStorage auditStorage) {
        this.auditStorage = auditStorage;
    }

    @GetMapping("/logs")
    public ApiResponse<List<AuditLogDTO>> queryLogs(
            @RequestParam(required = false) String lingId,
            @RequestParam(required = false) Long start,
            @RequestParam(required = false) Long end,
            @RequestParam(defaultValue = "100") int limit) {
        if (auditStorage == null) {
            return ApiResponse.error("审计存储未启用");
        }
        try {
            List<Map<String, Object>> rows = auditStorage.queryAuditLogs(lingId, start, end, limit);
            return ApiResponse.ok(rows == null ? Collections.emptyList() : rows.stream()
                    .map(this::toDto)
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            log.error("Failed to query audit logs", e);
            return ApiResponse.error("查询审计日志失败", e);
        }
    }

    private AuditLogDTO toDto(Map<String, Object> row) {
        return AuditLogDTO.builder()
                .timestamp(number(row.get("timestamp")))
                .lingId(string(row.get("ling_id")))
                .action(string(row.get("action")))
                .detail(string(row.get("detail")))
                .result(string(row.get("result")))
                .build();
    }

    private long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
