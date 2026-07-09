package com.lingframe.dashboard.storage;

import com.lingframe.core.metrics.JVMMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 指标数据存储单元测试
 * 覆盖：saveSnapshot / queryHistory（原始+聚合+默认时间范围） / cleanupBefore
 */
class MetricsStorageTest {

    private JdbcTemplate jdbcTemplate;
    private MetricsStorage storage;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        storage = new MetricsStorage(jdbcTemplate);
    }

    @Test
    @DisplayName("saveSnapshot 应执行 INSERT 并传入 22 个指标字段")
    void shouldSaveSnapshot() {
        JVMMetrics m = mock(JVMMetrics.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        storage.saveSnapshot(m);

        verify(jdbcTemplate).update(anyString(), any(Object[].class));
    }

    @Nested
    @DisplayName("queryHistory")
    class QueryHistoryTests {
        @Test
        @DisplayName("interval≤0 应返回原始数据点（不聚合）")
        void shouldReturnRawDataWhenNoInterval() {
            List<Map<String, Object>> expected = Collections.singletonList(Collections.singletonMap("cpu", 0.5));
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(expected);

            List<Map<String, Object>> result = storage.queryHistory(1000L, 2000L, 0);

            assertSame(expected, result);
            verify(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("interval>0 应按时间窗口聚合")
        void shouldAggregateWhenIntervalPositive() {
            List<Map<String, Object>> expected = Collections.emptyList();
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(expected);

            List<Map<String, Object>> result = storage.queryHistory(1000L, 2000L, 60);

            assertSame(expected, result);
            verify(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("end 为 null 应默认为当前时间")
        void shouldDefaultEndToNow() {
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(Collections.emptyList());

            storage.queryHistory(1000L, null, 0);

            verify(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("end≤0 应默认为当前时间")
        void shouldDefaultEndToNowWhenNonPositive() {
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(Collections.emptyList());

            storage.queryHistory(1000L, 0L, 0);

            verify(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("start 为 null 应默认为 end - 1 小时")
        void shouldDefaultStartToOneHourBeforeEnd() {
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(Collections.emptyList());

            storage.queryHistory(null, 2000L, 0);

            verify(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("start≤0 应默认为 end - 1 小时")
        void shouldDefaultStartWhenNonPositive() {
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(Collections.emptyList());

            storage.queryHistory(-1L, 2000L, 0);

            verify(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        }
    }

    @Test
    @DisplayName("cleanupBefore 应执行 DELETE 并返回删除行数")
    void shouldCleanupBefore() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(42);

        int deleted = storage.cleanupBefore(System.currentTimeMillis());

        assertEquals(42, deleted);
        verify(jdbcTemplate).update(anyString(), any(Object[].class));
    }
}
