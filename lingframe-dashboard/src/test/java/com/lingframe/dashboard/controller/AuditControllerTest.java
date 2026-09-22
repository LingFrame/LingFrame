package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.AuditLogDTO;
import com.lingframe.dashboard.storage.AuditStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("审计控制器测试")
class AuditControllerTest {

    @Test
    @DisplayName("查询审计日志应转换为稳定 DTO")
    void shouldReturnAuditLogs() {
        AuditStorage storage = mock(AuditStorage.class);
        Map<String, Object> row = new HashMap<>();
        row.put("timestamp", 123L);
        row.put("ling_id", "ling-1");
        row.put("action", "READ");
        row.put("detail", "{\"traceId\":\"t1\"}");
        row.put("result", "ALLOWED");
        when(storage.queryAuditLogs(isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(Collections.singletonList(row));

        ApiResponse<List<AuditLogDTO>> response = new AuditController(storage)
                .queryLogs(null, null, null, 100);

        assertTrue(response.isSuccess());
        assertEquals(1, response.getData().size());
        assertEquals("ling-1", response.getData().get(0).getLingId());
        assertEquals("ALLOWED", response.getData().get(0).getResult());
    }

    @Test
    @DisplayName("未启用审计存储时明确返回失败")
    void shouldRejectWhenStorageUnavailable() {
        ApiResponse<List<AuditLogDTO>> response = new AuditController(null)
                .queryLogs(null, null, null, 100);

        assertFalse(response.isSuccess());
        assertTrue(response.getMessage().contains("未启用"));
    }
}
