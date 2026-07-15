package com.lingframe.dashboard.service;

import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.metrics.MetricsSnapshot;
import com.lingframe.core.util.VersionUtils;
import com.lingframe.dashboard.dto.CanaryDecisionDTO;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 金丝雀发布决策辅助服务。
 * <p>
 * 决策规则（参考 spec B5）：
 * <ul>
 *   <li>金丝雀错误率 &gt; 稳定版 2x 且金丝雀错误率 &gt; 1% → ROLLBACK</li>
 *   <li>金丝雀 p99 &gt; 稳定版 1.5x → ROLLBACK</li>
 *   <li>金丝雀错误率 ≤ 稳定版 且 p99 ≤ 稳定版 且最近5分钟错误率波动 &lt; 0.5% → FULL_RELEASE</li>
 *   <li>其他 → OBSERVE</li>
 * </ul>
 * 数据样本不足（请求数过少）时返回 OBSERVE。
 * <p>
 * 错误率波动判断依赖内部维护的金丝雀版错误率历史采样（5分钟窗口），
 * 每次 decide 调用都会记录一次当前错误率，超出窗口的样本自动淘汰。
 */
@Slf4j
public class CanaryDecisionService {

    /** 最小样本请求数，低于此值视为数据不足 */
    private static final long MIN_SAMPLE_REQUESTS = 10L;
    /** 错误率回滚阈值（绝对值） */
    private static final double ERROR_RATE_ROLLBACK_ABSOLUTE = 0.01;
    /** 错误率回滚阈值（相对倍数） */
    private static final double ERROR_RATE_ROLLBACK_RATIO = 2.0;
    /** p99 延迟回滚阈值（相对倍数） */
    private static final double P99_ROLLBACK_RATIO = 1.5;
    /** FULL_RELEASE 的错误率波动上限（0.5%） */
    private static final double ERROR_RATE_FLUCTUATION_THRESHOLD = 0.005;
    /** 错误率历史窗口时长（5分钟） */
    private static final long HISTORY_WINDOW_MS = 5 * 60 * 1000L;
    /** 触发 FULL_RELEASE 波动判断所需的最小历史样本数 */
    private static final int MIN_HISTORY_SAMPLES = 3;

    private final MetricsCollector metricsCollector;

    /** 金丝雀错误率历史：key = lingId，value = 按时间排序的采样点队列 */
    private final Map<String, Deque<ErrorRateSample>> errorRateHistory = new ConcurrentHashMap<>();

    public CanaryDecisionService(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    /**
     * 根据灵元的稳定版与金丝雀版健康指标生成决策建议。
     * <p>
     * 版本角色判定：按版本号语义比较——版本号最高者视为金丝雀版（较新版本），
     * 次高者视为稳定版。这样不依赖 Map 迭代顺序，避免 HashMap 在不同 JVM 下
     * 因桶分布差异给出不一致的 stable/canary 判定。
     *
     * @param lingId 灵元 ID
     * @return 决策 DTO；若找不到对应版本快照则 sufficientData=false
     */
    public CanaryDecisionDTO decide(String lingId) {
        // 收集所有版本快照并按版本号降序排序：版本最高 = canary，次高 = stable
        List<MetricsSnapshot> snapshots = new ArrayList<>(
                metricsCollector.getVersionSnapshots(lingId).values());
        if (snapshots.size() >= 2) {
            // 按版本号降序排序：版本最高 = canary，次高 = stable
            snapshots.sort((a, b) -> VersionUtils.compareDescending(a.getVersion(), b.getVersion()));
        }
        MetricsSnapshot stable = snapshots.size() >= 2 ? snapshots.get(1) : null;
        MetricsSnapshot canary = snapshots.isEmpty() ? null : snapshots.get(0);

        // 数据不足：缺少任一版本快照，或样本过小
        if (stable == null || canary == null
                || stable.getTotalRequests() < MIN_SAMPLE_REQUESTS
                || canary.getTotalRequests() < MIN_SAMPLE_REQUESTS) {
            return CanaryDecisionDTO.builder()
                    .recommendation("OBSERVE")
                    .reason("数据样本不足，建议继续观察")
                    .reasonKey("canary.reason.insufficientData")
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

        // 记录金丝雀错误率采样，用于波动判断
        recordSample(lingId, canaryErrorRate);

        // 规则1：错误率显著恶化
        if (canaryErrorRate > stableErrorRate * ERROR_RATE_ROLLBACK_RATIO
                && canaryErrorRate > ERROR_RATE_ROLLBACK_ABSOLUTE) {
            return build("ROLLBACK", "金丝雀错误率显著高于稳定版", "canary.reason.errorRateDegraded",
                    stableErrorRate, canaryErrorRate, stableP99, canaryP99);
        }
        // 规则2：延迟显著恶化
        if (stableP99 > 0 && canaryP99 > stableP99 * P99_ROLLBACK_RATIO) {
            return build("ROLLBACK", "金丝雀延迟显著高于稳定版", "canary.reason.latencyDegraded",
                    stableErrorRate, canaryErrorRate, stableP99, canaryP99);
        }
        // 规则3：表现优于或持平稳定版，且错误率波动稳定
        if (canaryErrorRate <= stableErrorRate && canaryP99 <= stableP99) {
            double fluctuation = calculateErrorRateFluctuation(lingId);
            int sampleCount = getHistorySampleCount(lingId);
            if (sampleCount >= MIN_HISTORY_SAMPLES && fluctuation > ERROR_RATE_FLUCTUATION_THRESHOLD) {
                // 错误率波动过大，暂不建议全量发布
                return build("OBSERVE",
                        String.format("金丝雀指标持平但错误率波动较大(%.2f%%)，建议继续观察", fluctuation * 100),
                        "canary.reason.fluctuationHigh",
                        stableErrorRate, canaryErrorRate, stableP99, canaryP99);
            }
            String suffix = sampleCount < MIN_HISTORY_SAMPLES
                    ? "（历史样本不足，波动数据待积累）" : "";
            String reasonKey = sampleCount < MIN_HISTORY_SAMPLES
                    ? "canary.reason.stableButInsufficientHistory" : "canary.reason.stableAndSuperior";
            return build("FULL_RELEASE", "金丝雀表现优于或持平稳定版" + suffix, reasonKey,
                    stableErrorRate, canaryErrorRate, stableP99, canaryP99);
        }
        // 默认：继续观察
        return build("OBSERVE", "指标差异不显著，建议继续观察", "canary.reason.inconclusive",
                stableErrorRate, canaryErrorRate, stableP99, canaryP99);
    }

    /**
     * 记录金丝雀错误率采样，自动清理超出5分钟窗口的旧样本。
     */
    private void recordSample(String lingId, double errorRate) {
        long now = System.currentTimeMillis();
        Deque<ErrorRateSample> queue = errorRateHistory.computeIfAbsent(lingId, k -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(new ErrorRateSample(now, errorRate));
            // 清理超出窗口的旧样本
            long cutoff = now - HISTORY_WINDOW_MS;
            while (!queue.isEmpty() && queue.peekFirst().timestamp < cutoff) {
                queue.pollFirst();
            }
        }
    }

    /**
     * 计算最近5分钟内金丝雀错误率波动（max - min）。
     */
    private double calculateErrorRateFluctuation(String lingId) {
        Deque<ErrorRateSample> queue = errorRateHistory.get(lingId);
        if (queue == null || queue.isEmpty()) {
            return Double.MAX_VALUE; // 无历史数据视为波动未知，不满足 FULL_RELEASE
        }
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        synchronized (queue) {
            for (ErrorRateSample sample : queue) {
                if (sample.errorRate < min) min = sample.errorRate;
                if (sample.errorRate > max) max = sample.errorRate;
            }
        }
        return max - min;
    }

    /**
     * 获取历史样本数。
     */
    private int getHistorySampleCount(String lingId) {
        Deque<ErrorRateSample> queue = errorRateHistory.get(lingId);
        if (queue == null) return 0;
        synchronized (queue) {
            return queue.size();
        }
    }

    private CanaryDecisionDTO build(String recommendation, String reason, String reasonKey,
                                    double stableErrorRate, double canaryErrorRate,
                                    double stableP99, double canaryP99) {
        return CanaryDecisionDTO.builder()
                .recommendation(recommendation)
                .reason(reason)
                .reasonKey(reasonKey)
                .stableErrorRate(stableErrorRate)
                .canaryErrorRate(canaryErrorRate)
                .stableP99(stableP99)
                .canaryP99(canaryP99)
                .sufficientData(true)
                .build();
    }

    /** 错误率采样点 */
    private static final class ErrorRateSample {
        final long timestamp;
        final double errorRate;

        ErrorRateSample(long timestamp, double errorRate) {
            this.timestamp = timestamp;
            this.errorRate = errorRate;
        }
    }
}
