package com.lingframe.dashboard.service;

import com.lingframe.core.ling.LingUninstallResult;
import com.lingframe.core.spi.LeakRiskLevel;
import com.lingframe.core.spi.LeakRiskReport;
import com.lingframe.dashboard.dto.LingUninstallResultDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DashboardUninstallResultMapper 测试")
class DashboardUninstallResultMapperTest {

    @Test
    @DisplayName("应将卸载结果映射为 Dashboard DTO")
    void shouldMapUninstallResultToDto() {
        DashboardUninstallResultMapper mapper = new DashboardUninstallResultMapper();
        LeakRiskReport report = LeakRiskReport.riskDetected(
                "ling1",
                "1.0.0",
                "risk detected",
                Arrays.asList("thread=worker-1"),
                "test");
        LingUninstallResult result = LingUninstallResult.triggered("ling1", "1.0.0", Arrays.asList(report));

        LingUninstallResultDTO dto = mapper.toDto(result);

        assertEquals("ling1", dto.getLingId());
        assertEquals("1.0.0", dto.getVersion());
        assertTrue(dto.isUninstallTriggered());
        assertEquals(LeakRiskLevel.RISK_DETECTED, dto.getOverallRiskLevel());
        assertEquals(1, dto.getReports().size());
        assertEquals("thread=worker-1", dto.getReports().get(0).getDetails().get(0));
    }
}
