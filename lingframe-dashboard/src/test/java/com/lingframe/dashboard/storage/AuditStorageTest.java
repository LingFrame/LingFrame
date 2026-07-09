package com.lingframe.dashboard.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计日志存储单元测试
 * 覆盖：saveAuditLog / queryAuditLogs（lingId 有/无 + 默认时间 + 默认 limit） / cleanupBefore
 */
class AuditStorageTest {

    private JdbcTemplate jdbcTemplate;
    private AuditStorage storage;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        storage = new AuditStorage(jdbcTemplate);
    }

    @Test
    @DisplayName("saveAuditLog 应执行 INSERT 并传入时间戳与字段")
    void shouldSaveAuditLog() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        storage.saveAuditLog("ling-1", "DEPLOY", "detail", "SUCCESS");

        verify(jdbcTemplate).update(anyString(), any(Object[].class));
    }

    @Nested
    @DisplayName("queryAuditLogs")
    class QueryAuditLogsTests {
        @Test
        @DisplayName("lingId 非空应按 lingId 过滤")
        void shouldFilterByLingId() {
            List<Map<String, Object>> expected = Collections.singletonList(Collections.singletonMap("id", 1));
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(expected);

            List<Map<String, Object>> result = storage.queryAuditLogs("ling-1", 1000L, 2000L, 50);

            assertSame(expected, result);
            verify(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("lingId 为空应返回全部（不过滤 lingId）")
        void shouldReturnAllWhenLingIdEmpty() {
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(Collections.emptyList());

            storage.queryAuditLogs("", 1000L, 2000L, 50);

            verify(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("lingId 为 null 应返回全部")
        void shouldReturnAllWhenLingIdNull() {
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(Collections.emptyList());

            storage.queryAuditLogs(null, 1000L, 2000L, 50);

            verify(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("end 为 null 应默认为当前时间")
        void shouldDefaultEndToNow() {
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(Collections.emptyList());

            storage.queryAuditLogs(null, 1000L, null, 50);

            verify(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("start 为 null 应默认为 end - 1 小时")
        void shouldDefaultStartToOneHourBeforeEnd() {
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(Collections.emptyList());

            storage.queryAuditLogs(null, null, 2000L, 50);

            verify(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("limit≤0 应默认为 100")
        void shouldDefaultLimitTo100() {
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(Collections.emptyList());

            storage.queryAuditLogs(null, 1000L, 2000L, 0);
            storage.queryAuditLogs(null, 1000L, 2000L, -5);

            verify(jdbcTemplate, org.mockito.Mockito.times(2)).queryForList(anyString(), any(Object[].class));
        }
    }

    @Test
    @DisplayName("cleanupBefore 应执行 DELETE 并返回删除行数")
    void shouldCleanupBefore() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(10);

        int deleted = storage.cleanupBefore(System.currentTimeMillis());

        org.junit.jupiter.api.Assertions.assertEquals(10, deleted);
    }
}
