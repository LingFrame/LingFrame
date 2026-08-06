package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 契约迁移进度 DTO。
 * <p>
 * 描述某契约的「灵核 baseline vs 灵元」流量分布，
 * 供 Dashboard 迁移进度看板识别「可下线的灵核实现」。
 * <p>
 * 灵核/灵元区分仅在 Dashboard 层使用（运维视图），通过 lingId == LingCoreConstants.LINGCORE_LING_ID 判定。
 * 路由层不引用身份维度，观测层 ProviderMetricsCollector 也只按 contractId × lingId 二维统计。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractMigrationProgressDTO {

    /** 契约 ID */
    private String contractId;

    /** 灵核总调用量 */
    private long coreInvocations;

    /** 灵元总调用量 */
    private long lingInvocations;

    /** 总调用量 */
    private long totalInvocations;

    /** 灵核流量占比（0-1），无调用时返回 0 */
    private double coreTrafficRatio;

    /** 灵元流量占比（0-1），无调用时返回 0 */
    private double lingTrafficRatio;

    /** 灵核平均延迟（毫秒） */
    private double coreAvgDurationMs;

    /** 灵元平均延迟（毫秒） */
    private double lingAvgDurationMs;

    /** 灵核失败次数 */
    private long coreFailures;

    /** 灵元失败次数 */
    private long lingFailures;

    /** 是否灵核 0 调用（true 时页面高亮「该契约旧实现可下线」） */
    private boolean coreStale;

    /** 灵元 provider 数量 */
    private int providerCount;
}
