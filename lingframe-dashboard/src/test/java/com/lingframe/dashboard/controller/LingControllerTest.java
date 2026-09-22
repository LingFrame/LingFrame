package com.lingframe.dashboard.controller;

import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.routing.MigrationStateHolder;
import com.lingframe.core.spi.LeakRiskLevel;
import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.LeakRiskReportDTO;
import com.lingframe.dashboard.dto.LingInstanceSnapshotDTO;
import com.lingframe.dashboard.dto.LingUninstallResultDTO;
import com.lingframe.dashboard.service.DashboardService;
import com.lingframe.dashboard.service.ContractRoutingService;
import com.lingframe.dashboard.service.MetricsAggregationService;
import com.lingframe.dashboard.service.RuntimeDiagnosticsService;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("灵元控制器测试")
class LingControllerTest {

    @Test
    @DisplayName("卸载接口应把结构化预检结果返回给前端")
    void uninstallShouldReturnStructuredPrecheckResult() {
        DashboardService dashboardService = mock(DashboardService.class);
        MetricsCollector metricsCollector = mock(MetricsCollector.class);
        RuntimeDiagnosticsService runtimeDiagnosticsService = mock(RuntimeDiagnosticsService.class);
        LingFrameConfig config = mock(LingFrameConfig.class);
        LingController controller = new LingController(config, dashboardService, metricsCollector,
                runtimeDiagnosticsService,
                new MigrationStateHolder(), mock(ContractRoutingService.class),
                mock(MetricsAggregationService.class), false);

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
        when(dashboardService.uninstallLing("ling1", false)).thenReturn(dto);

        ApiResponse<LingUninstallResultDTO> response = controller.uninstall("ling1", false);

        assertTrue(response.isSuccess());
        assertNotNull(response.getData());
        assertEquals(LeakRiskLevel.RISK_DETECTED, response.getData().getOverallRiskLevel());
        assertEquals("ling1", response.getData().getLingId());
    }

    @Test
    @DisplayName("实例查询接口应返回按代次区分的运行事实")
    void getInstanceSnapshotsShouldReturnRuntimeFacts() {
        DashboardService dashboardService = mock(DashboardService.class);
        LingController controller = new LingController(mock(LingFrameConfig.class), dashboardService,
                mock(MetricsCollector.class), mock(RuntimeDiagnosticsService.class), new MigrationStateHolder(),
                mock(ContractRoutingService.class), mock(MetricsAggregationService.class), false);
        LingInstanceSnapshotDTO snapshot = LingInstanceSnapshotDTO.builder()
                .instanceId("instance-1").lingId("ling1").version("1.0.0")
                .status("READY").acceptingRequests(true).activeRequestCount(3).build();
        when(dashboardService.getInstanceSnapshots("ling1")).thenReturn(Arrays.asList(snapshot));

        ApiResponse<java.util.List<LingInstanceSnapshotDTO>> response = controller.getInstanceSnapshots("ling1");

        assertTrue(response.isSuccess());
        assertEquals("instance-1", response.getData().get(0).getInstanceId());
        assertEquals(3, response.getData().get(0).getActiveRequestCount());
    }

    @Test
    @DisplayName("卸载操作查询接口应返回已记录结果")
    void getUninstallOperationShouldReturnRecordedResult() {
        DashboardService dashboardService = mock(DashboardService.class);
        LingController controller = new LingController(mock(LingFrameConfig.class), dashboardService,
                mock(MetricsCollector.class), mock(RuntimeDiagnosticsService.class), new MigrationStateHolder(),
                mock(ContractRoutingService.class), mock(MetricsAggregationService.class), false);
        LingUninstallResultDTO result = LingUninstallResultDTO.builder()
                .operationId("op-1").lingId("ling1").build();
        when(dashboardService.getUninstallOperation("op-1")).thenReturn(result);

        ApiResponse<LingUninstallResultDTO> response = controller.getUninstallOperation("op-1");

        assertTrue(response.isSuccess());
        assertEquals("op-1", response.getData().getOperationId());
    }

    @Test
    @DisplayName("未知卸载操作应返回失败响应")
    void getUnknownUninstallOperationShouldReturnError() {
        DashboardService dashboardService = mock(DashboardService.class);
        LingController controller = new LingController(mock(LingFrameConfig.class), dashboardService,
                mock(MetricsCollector.class), mock(RuntimeDiagnosticsService.class), new MigrationStateHolder(),
                mock(ContractRoutingService.class), mock(MetricsAggregationService.class), false);
        when(dashboardService.getUninstallOperation("missing")).thenReturn(null);

        ApiResponse<LingUninstallResultDTO> response = controller.getUninstallOperation("missing");

        assertTrue(!response.isSuccess());
        assertTrue(response.getMessage().contains("不存在"));
    }
}
