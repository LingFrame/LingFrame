package com.lingframe.dashboard.controller;

import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.GovernanceMetricsSnapshot;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.metrics.MetricsSnapshot;
import com.lingframe.core.spi.LeakRiskLevel;
import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.LeakRiskReportDTO;
import com.lingframe.dashboard.dto.LingGovernanceMetricsViewDTO;
import com.lingframe.dashboard.dto.LingHealthViewDTO;
import com.lingframe.dashboard.dto.LingUninstallResultDTO;
import com.lingframe.dashboard.service.DashboardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("灵元控制器测试")
class LingControllerTest {

    @Test
    @DisplayName("卸载接口应把结构化预检结果返回给前端")
    void uninstallShouldReturnStructuredPrecheckResult() {
        DashboardService dashboardService = mock(DashboardService.class);
        MetricsCollector metricsCollector = mock(MetricsCollector.class);
        GovernanceMetricsCollector governanceMetricsCollector = mock(GovernanceMetricsCollector.class);
        LingFrameConfig config = mock(LingFrameConfig.class);
        LingController controller = new LingController(config, dashboardService, metricsCollector, governanceMetricsCollector, false);

        LingUninstallResultDTO dto = LingUninstallResultDTO.builder()
                .lingId("ling1")
                .uninstallTriggered(true)
                .overallRiskLevel(LeakRiskLevel.RISK_DETECTED)
                .reports(Collections.singletonList(LeakRiskReportDTO.builder()
                        .lingId("ling1")
                        .version("1.0.0")
                        .level(LeakRiskLevel.RISK_DETECTED)
                        .summary("risk detected")
                        .build()))
                .build();
        when(dashboardService.uninstallLing("ling1")).thenReturn(dto);

        ApiResponse<LingUninstallResultDTO> response = controller.uninstall("ling1");

        assertTrue(response.isSuccess());
        assertNotNull(response.getData());
        assertEquals(LeakRiskLevel.RISK_DETECTED, response.getData().getOverallRiskLevel());
        assertEquals("ling1", response.getData().getLingId());
    }

    @Test
    @DisplayName("健康指标总览接口应返回灵元摘要与版本明细")
    void getAllLingHealthShouldReturnSummaryAndVersions() {
        DashboardService dashboardService = mock(DashboardService.class);
        MetricsCollector metricsCollector = mock(MetricsCollector.class);
        GovernanceMetricsCollector governanceMetricsCollector = mock(GovernanceMetricsCollector.class);
        LingFrameConfig config = mock(LingFrameConfig.class);
        LingController controller = new LingController(config, dashboardService, metricsCollector, governanceMetricsCollector, false);

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
        DashboardService dashboardService = mock(DashboardService.class);
        MetricsCollector metricsCollector = mock(MetricsCollector.class);
        GovernanceMetricsCollector governanceMetricsCollector = mock(GovernanceMetricsCollector.class);
        LingFrameConfig config = mock(LingFrameConfig.class);
        LingController controller = new LingController(config, dashboardService, metricsCollector, governanceMetricsCollector, false);

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
}
