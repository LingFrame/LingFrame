package com.lingframe.dashboard.controller;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.metrics.MetricsSnapshot;
import com.lingframe.core.spi.ThreadPoolStatsProvider;
import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.JvmMetricsDTO;
import com.lingframe.dashboard.dto.LeakDetectionRecordDTO;
import com.lingframe.dashboard.dto.LingResourceMetricsDTO;
import com.lingframe.dashboard.dto.ThreadPoolStatsDTO;
import com.lingframe.dashboard.service.LeakDetectionCacheService;
import com.lingframe.dashboard.service.LingResourceMetricsCollector;
import com.lingframe.dashboard.service.RuntimeDiagnosticsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MetricsController 补充测试
 * <p>
 * 现有 MetricsControllerTest 仅覆盖 4 个查询接口。
 * 本类补齐 getJvmMetrics / getLingHealth / getLeakDetections /
 * getThreadPoolStats / getPerLingMetrics / getEventPipelineMetrics 共 6 个端点。
 */
@DisplayName("MetricsController 补充测试")
class MetricsControllerSupplementTest {

    private MetricsController newController(MetricsCollector metricsCollector,
            GovernanceMetricsCollector governanceMetricsCollector,
            RuntimeDiagnosticsService runtimeDiagnosticsService,
            LeakDetectionCacheService leakDetectionCacheService,
            LingResourceMetricsCollector lingResourceMetricsCollector,
            ThreadPoolStatsProvider threadPoolStatsProvider,
            EventBus eventBus) {
        return new MetricsController(metricsCollector, governanceMetricsCollector,
                runtimeDiagnosticsService, leakDetectionCacheService,
                lingResourceMetricsCollector, threadPoolStatsProvider, eventBus);
    }

    private MetricsController newControllerWithMock(EventBus eventBus) {
        return newController(
                mock(MetricsCollector.class),
                mock(GovernanceMetricsCollector.class),
                mock(RuntimeDiagnosticsService.class),
                mock(LeakDetectionCacheService.class),
                mock(LingResourceMetricsCollector.class),
                mock(ThreadPoolStatsProvider.class),
                eventBus);
    }

    // ==================== getJvmMetrics ====================

    @Nested
    @DisplayName("getJvmMetrics")
    class GetJvmMetricsTests {

        @Test
        @DisplayName("应成功返回 JVM 指标")
        void shouldReturnJvmMetrics() {
            MetricsController controller = newControllerWithMock(mock(EventBus.class));

            ApiResponse<JvmMetricsDTO> response = controller.getJvmMetrics();

            assertTrue(response.isSuccess());
            assertNotNull(response.getData());
            // 应能采集到基础字段
            assertNotNull(response.getData().getJvmVersion());
            assertNotNull(response.getData().getOsName());
            assertTrue(response.getData().getAvailableProcessors() > 0);
        }

        @Test
        @DisplayName("应正确解析 PID（不含 @ 后缀）")
        void shouldParsePidCorrectly() {
            MetricsController controller = newControllerWithMock(mock(EventBus.class));

            ApiResponse<JvmMetricsDTO> response = controller.getJvmMetrics();

            assertTrue(response.isSuccess());
            String pid = response.getData().getPid();
            assertNotNull(pid);
            // PID 不应包含 @
            assertFalse(pid.contains("@"));
        }
    }

    // ==================== getLingHealth ====================

    @Nested
    @DisplayName("getLingHealth")
    class GetLingHealthTests {

        @Test
        @DisplayName("应返回指定灵元的健康快照")
        void shouldReturnLingHealthSnapshot() {
            MetricsCollector metricsCollector = mock(MetricsCollector.class);
            MetricsSnapshot snapshot = new MetricsSnapshot();
            snapshot.setLingId("ling1");
            snapshot.setQps(100.0);
            when(metricsCollector.getSnapshot("ling1")).thenReturn(snapshot);
            MetricsController controller = newController(metricsCollector,
                    mock(GovernanceMetricsCollector.class), mock(RuntimeDiagnosticsService.class),
                    mock(LeakDetectionCacheService.class), mock(LingResourceMetricsCollector.class),
                    mock(ThreadPoolStatsProvider.class), mock(EventBus.class));

            ApiResponse<MetricsSnapshot> response = controller.getLingHealth("ling1");

            assertTrue(response.isSuccess());
            assertEquals("ling1", response.getData().getLingId());
            assertEquals(100.0, response.getData().getQps());
        }

        @Test
        @DisplayName("metricsCollector 抛异常时应返回 error")
        void shouldReturnErrorWhenMetricsCollectorThrows() {
            MetricsCollector metricsCollector = mock(MetricsCollector.class);
            when(metricsCollector.getSnapshot("ling1")).thenThrow(new RuntimeException("db error"));
            MetricsController controller = newController(metricsCollector,
                    mock(GovernanceMetricsCollector.class), mock(RuntimeDiagnosticsService.class),
                    mock(LeakDetectionCacheService.class), mock(LingResourceMetricsCollector.class),
                    mock(ThreadPoolStatsProvider.class), mock(EventBus.class));

            ApiResponse<MetricsSnapshot> response = controller.getLingHealth("ling1");

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("获取健康指标失败"));
        }
    }

    // ==================== getLeakDetections ====================

    @Nested
    @DisplayName("getLeakDetections")
    class GetLeakDetectionsTests {

        @Test
        @DisplayName("应返回泄漏检测记录列表")
        void shouldReturnLeakDetections() {
            LeakDetectionCacheService leakService = mock(LeakDetectionCacheService.class);
            LeakDetectionRecordDTO record = LeakDetectionRecordDTO.builder()
                    .lingId("ling1")
                    .version("1.0.0")
                    .collected(false)
                    .build();
            when(leakService.getRecords()).thenReturn(Collections.singletonList(record));
            MetricsController controller = newController(
                    mock(MetricsCollector.class), mock(GovernanceMetricsCollector.class),
                    mock(RuntimeDiagnosticsService.class), leakService,
                    mock(LingResourceMetricsCollector.class),
                    mock(ThreadPoolStatsProvider.class), mock(EventBus.class));

            ApiResponse<List<LeakDetectionRecordDTO>> response = controller.getLeakDetections();

            assertTrue(response.isSuccess());
            assertEquals(1, response.getData().size());
            assertEquals("ling1", response.getData().get(0).getLingId());
        }

        @Test
        @DisplayName("无记录时应返回空列表")
        void shouldReturnEmptyWhenNoRecords() {
            LeakDetectionCacheService leakService = mock(LeakDetectionCacheService.class);
            when(leakService.getRecords()).thenReturn(Collections.<LeakDetectionRecordDTO>emptyList());
            MetricsController controller = newController(
                    mock(MetricsCollector.class), mock(GovernanceMetricsCollector.class),
                    mock(RuntimeDiagnosticsService.class), leakService,
                    mock(LingResourceMetricsCollector.class),
                    mock(ThreadPoolStatsProvider.class), mock(EventBus.class));

            ApiResponse<List<LeakDetectionRecordDTO>> response = controller.getLeakDetections();

            assertTrue(response.isSuccess());
            assertTrue(response.getData().isEmpty());
        }
    }

    // ==================== getThreadPoolStats ====================

    @Nested
    @DisplayName("getThreadPoolStats")
    class GetThreadPoolStatsTests {

        @Test
        @DisplayName("应返回线程池状态列表并正确映射字段")
        void shouldReturnThreadPoolStats() {
            ThreadPoolStatsProvider provider = mock(ThreadPoolStatsProvider.class);
            ThreadPoolStatsProvider.ThreadPoolStats stats = new ThreadPoolStatsProvider.ThreadPoolStats(
                    "ling1", 5, 10, 20, 3, 100L);
            when(provider.getThreadPoolStats()).thenReturn(Collections.singletonList(stats));
            MetricsController controller = newController(
                    mock(MetricsCollector.class), mock(GovernanceMetricsCollector.class),
                    mock(RuntimeDiagnosticsService.class), mock(LeakDetectionCacheService.class),
                    mock(LingResourceMetricsCollector.class), provider, mock(EventBus.class));

            ApiResponse<List<ThreadPoolStatsDTO>> response = controller.getThreadPoolStats();

            assertTrue(response.isSuccess());
            assertEquals(1, response.getData().size());
            ThreadPoolStatsDTO dto = response.getData().get(0);
            assertEquals("ling1", dto.getLingId());
            assertEquals(5, dto.getActiveCount());
            assertEquals(10, dto.getPoolSize());
            assertEquals(20, dto.getMaxThreads());
            assertEquals(3, dto.getQueueSize());
            assertEquals(100L, dto.getCompletedTaskCount());
        }

        @Test
        @DisplayName("多灵元线程池应全部返回")
        void shouldReturnMultipleThreadPools() {
            ThreadPoolStatsProvider provider = mock(ThreadPoolStatsProvider.class);
            when(provider.getThreadPoolStats()).thenReturn(Arrays.asList(
                    new ThreadPoolStatsProvider.ThreadPoolStats("ling1", 1, 2, 4, 0, 10L),
                    new ThreadPoolStatsProvider.ThreadPoolStats("ling2", 3, 6, 8, 2, 20L)));
            MetricsController controller = newController(
                    mock(MetricsCollector.class), mock(GovernanceMetricsCollector.class),
                    mock(RuntimeDiagnosticsService.class), mock(LeakDetectionCacheService.class),
                    mock(LingResourceMetricsCollector.class), provider, mock(EventBus.class));

            ApiResponse<List<ThreadPoolStatsDTO>> response = controller.getThreadPoolStats();

            assertTrue(response.isSuccess());
            assertEquals(2, response.getData().size());
        }
    }

    // ==================== getPerLingMetrics ====================

    @Nested
    @DisplayName("getPerLingMetrics")
    class GetPerLingMetricsTests {

        @Test
        @DisplayName("应返回灵元资源指标列表")
        void shouldReturnPerLingMetrics() {
            LingResourceMetricsCollector collector = mock(LingResourceMetricsCollector.class);
            LingResourceMetricsDTO dto = LingResourceMetricsDTO.builder()
                    .lingId("ling1")
                    .version("1.0.0")
                    .loadedClassCount(100)
                    .activeThreadCount(5)
                    .cpuTimeMs(1000)
                    .build();
            when(collector.getMetrics()).thenReturn(Collections.singletonList(dto));
            MetricsController controller = newController(
                    mock(MetricsCollector.class), mock(GovernanceMetricsCollector.class),
                    mock(RuntimeDiagnosticsService.class), mock(LeakDetectionCacheService.class),
                    collector, mock(ThreadPoolStatsProvider.class), mock(EventBus.class));

            ApiResponse<List<LingResourceMetricsDTO>> response = controller.getPerLingMetrics();

            assertTrue(response.isSuccess());
            assertEquals(1, response.getData().size());
            assertEquals("ling1", response.getData().get(0).getLingId());
            assertEquals(100, response.getData().get(0).getLoadedClassCount());
        }
    }

    // ==================== getEventPipelineMetrics ====================

    @Nested
    @DisplayName("getEventPipelineMetrics")
    class GetEventPipelineMetricsTests {

        @Test
        @DisplayName("应返回 EventBus 和 AuditManager 的内部指标")
        void shouldReturnEventPipelineMetrics() {
            EventBus eventBus = mock(EventBus.class);
            when(eventBus.getDroppedAsyncEvents()).thenReturn(5L);
            when(eventBus.getSubmittedAsyncEvents()).thenReturn(1000L);
            when(eventBus.getQueueSize()).thenReturn(10);
            when(eventBus.getQueueRemainingCapacity()).thenReturn(990);
            when(eventBus.getOverflowPolicy()).thenReturn(EventBus.OverflowPolicy.BLOCK);
            MetricsController controller = newControllerWithMock(eventBus);

            ApiResponse<Map<String, Object>> response = controller.getEventPipelineMetrics();

            assertTrue(response.isSuccess());
            Map<String, Object> data = response.getData();
            assertNotNull(data);
            assertEquals(5L, data.get("eventBusDroppedCount"));
            assertEquals(1000L, data.get("eventBusSubmittedCount"));
            assertEquals(10, data.get("eventBusQueueSize"));
            assertEquals(990, data.get("eventBusQueueRemainingCapacity"));
            assertEquals("BLOCK", data.get("eventBusOverflowPolicy"));
            assertNotNull(data.get("auditOverflowPolicy"));
            assertNotNull(data.get("auditShutdown"));
        }

        @Test
        @DisplayName("eventBus 抛异常时应返回 error")
        void shouldReturnErrorWhenEventBusThrows() {
            EventBus eventBus = mock(EventBus.class);
            when(eventBus.getDroppedAsyncEvents()).thenThrow(new RuntimeException("bus error"));
            MetricsController controller = newControllerWithMock(eventBus);

            ApiResponse<Map<String, Object>> response = controller.getEventPipelineMetrics();

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("获取事件管道指标失败"));
        }
    }
}
