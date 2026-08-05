package com.lingframe.dashboard.controller;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.GovernanceMetricsSnapshot;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.metrics.MetricsSnapshot;
import com.lingframe.core.spi.ThreadPoolStatsProvider;
import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.LingGovernanceMetricsViewDTO;
import com.lingframe.dashboard.dto.LingHealthViewDTO;
import com.lingframe.dashboard.dto.ResourceCleanupCapabilityDTO;
import com.lingframe.dashboard.dto.RuntimeGovernanceReadinessDTO;
import com.lingframe.dashboard.service.LeakDetectionCacheService;
import com.lingframe.dashboard.service.LingResourceMetricsCollector;
import com.lingframe.dashboard.service.MetricsAggregationService;
import com.lingframe.dashboard.service.RuntimeDiagnosticsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("指标控制器测试")
class MetricsControllerTest {

    @Test
    @DisplayName("健康指标总览接口应返回灵元摘要与版本明细")
    void getAllLingHealthShouldReturnSummaryAndVersions() {
        MetricsCollector metricsCollector = mock(MetricsCollector.class);
        MetricsController controller = new MetricsController(
                metricsCollector,
                mock(GovernanceMetricsCollector.class),
                mock(RuntimeDiagnosticsService.class),
                mock(LeakDetectionCacheService.class),
                mock(LingResourceMetricsCollector.class),
                mock(ThreadPoolStatsProvider.class),
                mock(EventBus.class),
                new MetricsAggregationService(metricsCollector, mock(GovernanceMetricsCollector.class)));

        MetricsSnapshot summary = new MetricsSnapshot();
        summary.setLingId("ling1");
        summary.setQps(18.5);
        summary.setHealthStatus(MetricsSnapshot.HealthStatus.WARNING);

        MetricsSnapshot stable = new MetricsSnapshot();
        stable.setLingId("ling1");
        stable.setVersion("1.0.0");
        stable.setQps(10.0);

        MetricsSnapshot canary = new MetricsSnapshot();
        canary.setLingId("ling1");
        canary.setVersion("1.1.0");
        canary.setQps(8.5);

        Map<String, MetricsSnapshot> versions = new LinkedHashMap<>();
        versions.put("1.0.0", stable);
        versions.put("1.1.0", canary);

        when(metricsCollector.getAllSnapshots()).thenReturn(Collections.singletonList(summary));
        when(metricsCollector.getVersionSnapshots("ling1")).thenReturn(versions);

        ApiResponse<Map<String, LingHealthViewDTO>> response = controller.getAllLingHealth();

        assertTrue(response.isSuccess());
        assertNotNull(response.getData());
        assertFalse(response.getData().isEmpty());
        LingHealthViewDTO dto = response.getData().get("ling1");
        assertNotNull(dto);
        assertEquals(MetricsSnapshot.HealthStatus.WARNING, dto.getSummary().getHealthStatus());
        assertEquals(2, dto.getVersions().size());
        assertEquals(8.5, dto.getVersions().get("1.1.0").getQps());
    }

    @Test
    @DisplayName("治理指标总览接口应返回灵元摘要与版本明细")
    void getAllLingGovernanceMetricsShouldReturnSummaryAndVersions() {
        GovernanceMetricsCollector governanceMetricsCollector = mock(GovernanceMetricsCollector.class);
        MetricsController controller = new MetricsController(
                mock(MetricsCollector.class),
                governanceMetricsCollector,
                mock(RuntimeDiagnosticsService.class),
                mock(LeakDetectionCacheService.class),
                mock(LingResourceMetricsCollector.class),
                mock(ThreadPoolStatsProvider.class),
                mock(EventBus.class),
                new MetricsAggregationService(mock(MetricsCollector.class), governanceMetricsCollector));

        GovernanceMetricsSnapshot summary = new GovernanceMetricsSnapshot();
        summary.setLingId("ling1");
        summary.setRateLimitedRequests(3);

        GovernanceMetricsSnapshot stable = new GovernanceMetricsSnapshot();
        stable.setLingId("ling1");
        stable.setVersion("1.0.0");
        stable.setRateLimitedRequests(1);

        GovernanceMetricsSnapshot canary = new GovernanceMetricsSnapshot();
        canary.setLingId("ling1");
        canary.setVersion("1.1.0");
        canary.setTimeoutRequests(2);

        Map<String, GovernanceMetricsSnapshot> versions = new LinkedHashMap<>();
        versions.put("1.0.0", stable);
        versions.put("1.1.0", canary);

        when(governanceMetricsCollector.getAllSummaries()).thenReturn(Collections.singletonMap("ling1", summary));
        when(governanceMetricsCollector.getVersionSnapshots("ling1")).thenReturn(versions);

        ApiResponse<Map<String, LingGovernanceMetricsViewDTO>> response = controller.getAllLingGovernanceMetrics();

        assertTrue(response.isSuccess());
        assertNotNull(response.getData());
        LingGovernanceMetricsViewDTO dto = response.getData().get("ling1");
        assertNotNull(dto);
        assertEquals(3, dto.getSummary().getRateLimitedRequests());
        assertEquals(2, dto.getVersions().get("1.1.0").getTimeoutRequests());
    }

    @Test
    @DisplayName("运行时诊断接口应返回资源清理能力快照")
    void getRuntimeDiagnosticsShouldReturnCleanupCapabilities() {
        RuntimeDiagnosticsService runtimeDiagnosticsService = mock(RuntimeDiagnosticsService.class);
        MetricsController controller = new MetricsController(
                mock(MetricsCollector.class),
                mock(GovernanceMetricsCollector.class),
                runtimeDiagnosticsService,
                mock(LeakDetectionCacheService.class),
                mock(LingResourceMetricsCollector.class),
                mock(ThreadPoolStatsProvider.class),
                mock(EventBus.class),
                mock(MetricsAggregationService.class));

        ResourceCleanupCapabilityDTO dto = ResourceCleanupCapabilityDTO.builder()
                .runtime("BasicUnloadHook")
                .jdkVersion(17)
                .threadTargetAccessible(false)
                .driverManagerAccessible(false)
                .summary("jdk=17,target=false")
                .build();
        Map<String, ResourceCleanupCapabilityDTO> diagnostics = new LinkedHashMap<>();
        diagnostics.put("BasicUnloadHook", dto);
        when(runtimeDiagnosticsService.getCleanupCapabilities()).thenReturn(diagnostics);

        ApiResponse<Map<String, ResourceCleanupCapabilityDTO>> response = controller.getRuntimeDiagnostics();

        assertTrue(response.isSuccess());
        assertNotNull(response.getData());
        assertEquals(1, response.getData().size());
        assertEquals(17, response.getData().get("BasicUnloadHook").getJdkVersion());
    }

    @Test
    @DisplayName("运行时治理就绪度接口应返回统一诊断结论")
    void getRuntimeGovernanceReadinessShouldReturnUnifiedReadinessView() {
        RuntimeDiagnosticsService runtimeDiagnosticsService = mock(RuntimeDiagnosticsService.class);
        MetricsController controller = new MetricsController(
                mock(MetricsCollector.class),
                mock(GovernanceMetricsCollector.class),
                runtimeDiagnosticsService,
                mock(LeakDetectionCacheService.class),
                mock(LingResourceMetricsCollector.class),
                mock(ThreadPoolStatsProvider.class),
                mock(EventBus.class),
                mock(MetricsAggregationService.class));

        RuntimeGovernanceReadinessDTO dto = RuntimeGovernanceReadinessDTO.builder()
                .status("LIMITED")
                .summary("Runtime governance is active, but some diagnostics indicate capability limits.")
                .sharedApiBoundaryFrozen(true)
                .diagnosticsCount(2)
                .warnings(Collections.singletonList("BasicUnloadHook: DriverManager cleanup is unavailable on this JVM"))
                .build();
        when(runtimeDiagnosticsService.getGovernanceReadiness()).thenReturn(dto);

        ApiResponse<RuntimeGovernanceReadinessDTO> response = controller.getRuntimeGovernanceReadiness();

        assertTrue(response.isSuccess());
        assertNotNull(response.getData());
        assertEquals("LIMITED", response.getData().getStatus());
        assertTrue(response.getData().isSharedApiBoundaryFrozen());
        assertEquals(2, response.getData().getDiagnosticsCount());
    }
}
