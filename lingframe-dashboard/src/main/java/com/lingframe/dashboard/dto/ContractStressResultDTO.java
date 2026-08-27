package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 契约级流量演练/压测结果 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractStressResultDTO {

    /** 契约 ID */
    private String contractId;

    /** 压测总轮数 */
    private int totalRounds;

    /** 总耗时（毫秒） */
    private long totalDurationMs;

    /** 平均耗时（毫秒） */
    private double avgLatencyMs;

    /** 各 Provider 的分流统计明细 */
    private List<ProviderTrafficStat> stats;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProviderTrafficStat {
        /** 提供方路由键（例如 lingcore-app, ling-storage-oss:1.0.0） */
        private String providerKey;

        /** 提供方灵元 ID / 灵核标识 */
        private String lingId;

        /** 提供方版本（灵核为 null） */
        private String version;

        /** 类型标识：CORE / LING */
        private String type;

        /** 当前生效的设定权重（0-100） */
        private int configuredWeight;

        /** 实际命中次数 */
        private int hitCount;

        /** 实际命中百分比（0.0 - 100.0） */
        private double actualPercent;

        /** 理论设定百分比（0.0 - 100.0） */
        private double expectedPercent;

        /** 分流偏差（实际百分比 - 理论百分比） */
        private double drift;
    }
}
