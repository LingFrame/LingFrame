package com.lingframe.dashboard.dto;

import com.lingframe.dashboard.service.DashboardService;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 仪表盘核心监控数据聚合，用于合并多次轮询请求
 */
@Data
@Builder
public class DashboardSummaryDTO {
    /** 灵元健康指标 */
    private Map<String, LingHealthViewDTO> healthMetrics;

    /** 灵元治理指标 */
    private Map<String, LingGovernanceMetricsViewDTO> governanceMetrics;

    /** 运行时诊断能力 */
    private Map<String, ResourceCleanupCapabilityDTO> runtimeDiagnostics;

    /** 运行时治理就绪度 */
    private RuntimeGovernanceReadinessDTO runtimeGovernanceReadiness;

    /** 最近生命周期事件（取最近10条，用于概览页展示） */
    private List<DashboardService.LifecycleEvent> recentEvents;
}
