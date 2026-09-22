package com.lingframe.dashboard.service;

import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.GovernanceMetricsSnapshot;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.metrics.MetricsSnapshot;
import com.lingframe.dashboard.dto.LingGovernanceMetricsViewDTO;
import com.lingframe.dashboard.dto.LingHealthViewDTO;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 指标聚合服务：把「所有灵元健康/治理指标总览（摘要 + 版本明细）」的聚合逻辑收敛到一处。
 * <p>
 * {@code MetricsController} 与 {@code LingController.getDashboardSummary} 的
 * healthMetrics / governanceMetrics 聚合逻辑由本服务统一提供，两个 controller 均委托本服务，
 * 消除重复、保证口径一致。
 */
@RequiredArgsConstructor
public class MetricsAggregationService {

    private final MetricsCollector metricsCollector;
    private final GovernanceMetricsCollector governanceMetricsCollector;

    /**
     * 聚合所有灵元的健康指标总览（摘要 + 版本明细）。
     */
    public Map<String, LingHealthViewDTO> getAllHealthView() {
        return metricsCollector.getAllSnapshots().stream()
                .collect(Collectors.toMap(
                        MetricsSnapshot::getLingId,
                        snapshot -> LingHealthViewDTO.builder()
                                .summary(snapshot)
                                .versions(metricsCollector.getVersionSnapshots(snapshot.getLingId()))
                                .build(),
                        (existing, replacement) -> replacement
                ));
    }

    /**
     * 聚合所有灵元的治理指标总览（摘要 + 版本明细）。
     */
    public Map<String, LingGovernanceMetricsViewDTO> getAllGovernanceView() {
        return governanceMetricsCollector.getAllSummaries().values().stream()
                .collect(Collectors.toMap(
                        GovernanceMetricsSnapshot::getLingId,
                        snapshot -> LingGovernanceMetricsViewDTO.builder()
                                .summary(snapshot)
                                .versions(governanceMetricsCollector.getVersionSnapshots(snapshot.getLingId()))
                                .build(),
                        (existing, replacement) -> replacement
                ));
    }
}
