package com.lingframe.dashboard.service;

import com.lingframe.core.ling.LingUninstallResult;
import com.lingframe.dashboard.dto.LeakRiskReportDTO;
import com.lingframe.dashboard.dto.LingUninstallResultDTO;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Dashboard 卸载结果转换器。
 */
public class DashboardUninstallResultMapper {

    public LingUninstallResultDTO toDto(LingUninstallResult result) {
        List<LeakRiskReportDTO> reports = result.getReports().stream()
                .map(report -> LeakRiskReportDTO.builder()
                        .lingId(report.getLingId())
                        .version(report.getVersion())
                        .level(report.getLevel())
                        .summary(report.getSummary())
                        .details(report.getDetails())
                        .checker(report.getChecker())
                        .timestamp(report.getTimestamp())
                        .build())
                .collect(Collectors.toList());

        return LingUninstallResultDTO.builder()
                .lingId(result.getLingId())
                .version(result.getVersion())
                .uninstallTriggered(result.isUninstallTriggered())
                .overallRiskLevel(result.getOverallRiskLevel())
                .reports(reports)
                .build();
    }
}
