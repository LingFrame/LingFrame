package com.lingframe.dashboard.service;

import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.metrics.MetricsSnapshot;
import com.lingframe.dashboard.dto.CanaryDecisionDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 金丝雀发布决策辅助服务。
 * <p>
 * 决策规则（参考 spec B5）：
 * <ul>
 *   <li>金丝雀错误率 &gt; 稳定版 2x 且金丝雀错误率 &gt; 1% → ROLLBACK</li>
 *   <li>金丝雀 p99 &gt; 稳定版 1.5x → ROLLBACK</li>
 *   <li>金丝雀错误率 ≤ 稳定版 且 p99 ≤ 稳定版 → FULL_RELEASE</li>
 *   <li>其他 → OBSERVE</li>
 * </ul>
 * 数据样本不足（请求数过少）时返回 OBSERVE。
 */
@Slf4j
@Service
public class CanaryDecisionService {

    /** 最小样本请求数，低于此值视为数据不足 */
    private static final long MIN_SAMPLE_REQUESTS = 10L;
    /** 错误率回滚阈值（绝对值） */
    private static final double ERROR_RATE_ROLLBACK_ABSOLUTE = 0.01;
    /** 错误率回滚阈值（相对倍数） */
    private static final double ERROR_RATE_ROLLBACK_RATIO = 2.0;
    /** p99 延迟回滚阈值（相对倍数） */
    private static final double P99_ROLLBACK_RATIO = 1.5;

    private final MetricsCollector metricsCollector;

    public CanaryDecisionService(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    /**
     * 根据灵元的稳定版与金丝雀版健康指标生成决策建议。
     *
     * @param lingId 灵元 ID
     * @return 决策 DTO；若找不到对应版本快照则 sufficientData=false
     */
    public CanaryDecisionDTO decide(String lingId) {
        MetricsSnapshot stable = null;
        MetricsSnapshot canary = null;
        for (MetricsSnapshot snapshot : metricsCollector.getVersionSnapshots(lingId).values()) {
            // 通过版本快照定位稳定版与金丝雀版；MetricsSnapshot 不直接携带角色标记，
            // 这里借助 qps>0 与 totalRequests 作为活跃版本判据，由调用方保证版本语义。
            // 实际角色判定依赖 DashboardService 的 VersionInfo，此处简化处理：
            // 取第一个快照为稳定版，第二个为金丝雀版（与金丝雀场景一致）。
            if (stable == null) {
                stable = snapshot;
            } else if (canary == null) {
                canary = snapshot;
            }
        }

        // 数据不足：缺少任一版本快照，或样本过小
        if (stable == null || canary == null
                || stable.getTotalRequests() < MIN_SAMPLE_REQUESTS
                || canary.getTotalRequests() < MIN_SAMPLE_REQUESTS) {
            return CanaryDecisionDTO.builder()
                    .recommendation("OBSERVE")
                    .reason("数据样本不足，建议继续观察")
                    .stableErrorRate(stable != null ? stable.getErrorRate() : 0)
                    .canaryErrorRate(canary != null ? canary.getErrorRate() : 0)
                    .stableP99(stable != null ? stable.getP99LatencyMs() : 0)
                    .canaryP99(canary != null ? canary.getP99LatencyMs() : 0)
                    .sufficientData(false)
                    .build();
        }

        double stableErrorRate = stable.getErrorRate();
        double canaryErrorRate = canary.getErrorRate();
        double stableP99 = stable.getP99LatencyMs();
        double canaryP99 = canary.getP99LatencyMs();

        // 规则1：错误率显著恶化
        if (canaryErrorRate > stableErrorRate * ERROR_RATE_ROLLBACK_RATIO
                && canaryErrorRate > ERROR_RATE_ROLLBACK_ABSOLUTE) {
            return build("ROLLBACK", "金丝雀错误率显著高于稳定版",
                    stableErrorRate, canaryErrorRate, stableP99, canaryP99);
        }
        // 规则2：延迟显著恶化
        if (stableP99 > 0 && canaryP99 > stableP99 * P99_ROLLBACK_RATIO) {
            return build("ROLLBACK", "金丝雀延迟显著高于稳定版",
                    stableErrorRate, canaryErrorRate, stableP99, canaryP99);
        }
        // 规则3：表现优于或持平稳定版
        if (canaryErrorRate <= stableErrorRate && canaryP99 <= stableP99) {
            return build("FULL_RELEASE", "金丝雀表现优于或持平稳定版",
                    stableErrorRate, canaryErrorRate, stableP99, canaryP99);
        }
        // 默认：继续观察
        return build("OBSERVE", "指标差异不显著，建议继续观察",
                stableErrorRate, canaryErrorRate, stableP99, canaryP99);
    }

    private CanaryDecisionDTO build(String recommendation, String reason,
                                    double stableErrorRate, double canaryErrorRate,
                                    double stableP99, double canaryP99) {
        return CanaryDecisionDTO.builder()
                .recommendation(recommendation)
                .reason(reason)
                .stableErrorRate(stableErrorRate)
                .canaryErrorRate(canaryErrorRate)
                .stableP99(stableP99)
                .canaryP99(canaryP99)
                .sufficientData(true)
                .build();
    }
}
