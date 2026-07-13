package com.lingframe.dashboard.dto;

import com.lingframe.core.ling.ProviderKind;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 契约迁移进度 DTO。
 * <p>
 * 描述某契约的「灵核 vs 灵元」流量分布，
 * 供 Dashboard 迁移进度看板识别「可下线的灵核实现」。
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
