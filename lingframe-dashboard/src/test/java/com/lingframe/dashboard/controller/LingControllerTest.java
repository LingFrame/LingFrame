package com.lingframe.dashboard.controller;

import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.routing.MigrationStateHolder;
import com.lingframe.core.spi.LeakRiskLevel;
import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.LeakRiskReportDTO;
import com.lingframe.dashboard.dto.LingUninstallResultDTO;
import com.lingframe.dashboard.service.DashboardService;
import com.lingframe.dashboard.service.MetricsAggregationService;
import com.lingframe.dashboard.service.RuntimeDiagnosticsService;
import com.lingframe.dashboard.service.ContractRoutingService;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
}
