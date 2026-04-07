package com.lingframe.dashboard.dto;

import com.lingframe.core.spi.LeakRiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LingUninstallResultDTO {
    private String lingId;
    private String version;
    private boolean uninstallTriggered;
    private LeakRiskLevel overallRiskLevel;
    private List<LeakRiskReportDTO> reports;
}
