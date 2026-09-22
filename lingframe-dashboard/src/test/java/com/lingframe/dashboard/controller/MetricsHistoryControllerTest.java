package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.storage.MetricsStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 历史指标控制器测试
 * 覆盖 getHistory 端点的正常路径、默认参数透传与异常路径
 */
@DisplayName("历史指标控制器测试")
class MetricsHistoryControllerTest {

    private MetricsStorage metricsStorage;
    private MetricsHistoryController controller;

    @BeforeEach
    void setUp() {
        metricsStorage = mock(MetricsStorage.class);
        controller = new MetricsHistoryController(metricsStorage);
    }

    @Nested
    @DisplayName("getHistory")
    class GetHistoryTests {

        @Test
        @DisplayName("正常返回历史指标数据")
        void shouldReturnHistory() {
            List<Map<String, Object>> data = Arrays.asList(
                    Collections.singletonMap("cpu", 0.5),
                    Collections.singletonMap("cpu", 0.8)
            );
            when(metricsStorage.queryHistory(1000L, 2000L, 60)).thenReturn(data);

            ApiResponse<List<Map<String, Object>>> response =
                    controller.getHistory(1000L, 2000L, 60);

            assertTrue(response.isSuccess());
            assertEquals(2, response.getData().size());
        }

        @Test
        @DisplayName("参数为 null 时应透传给 storage（由 storage 处理默认值）")
        void shouldPassNullParamsToStorage() {
            when(metricsStorage.queryHistory(null, null, 0)).thenReturn(Collections.emptyList());

            ApiResponse<List<Map<String, Object>>> response =
                    controller.getHistory(null, null, 0);

            assertTrue(response.isSuccess());
            assertTrue(response.getData().isEmpty());
        }

        @Test
        @DisplayName("storage 抛异常时返回 error")
        void shouldReturnErrorOnException() {
            when(metricsStorage.queryHistory(any(), any(), anyInt()))
                    .thenThrow(new RuntimeException("db error"));

            ApiResponse<List<Map<String, Object>>> response =
                    controller.getHistory(null, null, 0);

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("查询历史指标失败"));
        }
    }
}
