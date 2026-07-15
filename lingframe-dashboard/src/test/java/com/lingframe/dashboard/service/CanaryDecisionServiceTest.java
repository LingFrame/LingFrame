package com.lingframe.dashboard.service;

import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.metrics.MetricsSnapshot;
import com.lingframe.dashboard.dto.CanaryDecisionDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link CanaryDecisionService} 单元测试。
 */
class CanaryDecisionServiceTest {

    private MetricsSnapshot snapshot(double errorRate, long p99, long totalRequests) {
        MetricsSnapshot s = new MetricsSnapshot();
        s.setErrorRate(errorRate);
        s.setP99LatencyMs(p99);
        s.setTotalRequests(totalRequests);
        return s;
    }

    private Map<String, MetricsSnapshot> mapOf(MetricsSnapshot... snapshots) {
        Map<String, MetricsSnapshot> map = new HashMap<>();
        for (int i = 0; i < snapshots.length; i++) {
            // 🔥 使用语义化版本号，使 P1-34 版本语义排序能正确区分 stable/canary
            // 版本号递增：第一个=1.0.0(stable)，第二个=2.0.0(canary)
            String version = (i + 1) + ".0.0";
            snapshots[i].setVersion(version);
            map.put(version, snapshots[i]);
        }
        return map;
    }

    @Test
    @DisplayName("数据样本不足时返回 OBSERVE 且 sufficientData=false")
    void decide_insufficientData_returnsObserve() {
        MetricsCollector collector = Mockito.mock(MetricsCollector.class);
        when(collector.getVersionSnapshots("ling1"))
                .thenReturn(mapOf(
                        snapshot(0.001, 100, 5),   // 样本不足
                        snapshot(0.002, 120, 100)));

        CanaryDecisionService service = new CanaryDecisionService(collector);
        CanaryDecisionDTO result = service.decide("ling1");

        assertEquals("OBSERVE", result.getRecommendation());
        assertFalse(result.isSufficientData());
    }

    @Test
    @DisplayName("金丝雀错误率显著高于稳定版时返回 ROLLBACK")
    void decide_errorRateSpike_returnsRollback() {
        MetricsCollector collector = Mockito.mock(MetricsCollector.class);
        when(collector.getVersionSnapshots("ling1"))
                .thenReturn(mapOf(
                        snapshot(0.005, 100, 1000),   // 稳定版错误率 0.5%
                        snapshot(0.05, 110, 1000)));   // 金丝雀错误率 5%，>2x 且 >1%

        CanaryDecisionService service = new CanaryDecisionService(collector);
        CanaryDecisionDTO result = service.decide("ling1");

        assertEquals("ROLLBACK", result.getRecommendation());
        assertTrue(result.isSufficientData());
    }

    @Test
    @DisplayName("金丝雀 p99 显著高于稳定版时返回 ROLLBACK")
    void decide_p99Spike_returnsRollback() {
        MetricsCollector collector = Mockito.mock(MetricsCollector.class);
        when(collector.getVersionSnapshots("ling1"))
                .thenReturn(mapOf(
                        snapshot(0.001, 100, 1000),
                        snapshot(0.001, 200, 1000)));  // p99 2x，>1.5x

        CanaryDecisionService service = new CanaryDecisionService(collector);
        CanaryDecisionDTO result = service.decide("ling1");

        assertEquals("ROLLBACK", result.getRecommendation());
    }

    @Test
    @DisplayName("金丝雀表现优于或持平稳定版时返回 FULL_RELEASE")
    void decide_betterOrEqual_returnsFullRelease() {
        MetricsCollector collector = Mockito.mock(MetricsCollector.class);
        when(collector.getVersionSnapshots("ling1"))
                .thenReturn(mapOf(
                        snapshot(0.01, 150, 1000),
                        snapshot(0.005, 140, 1000)));  // 错误率更低，p99 更低

        CanaryDecisionService service = new CanaryDecisionService(collector);
        CanaryDecisionDTO result = service.decide("ling1");

        assertEquals("FULL_RELEASE", result.getRecommendation());
    }

    @Test
    @DisplayName("指标差异不显著时返回 OBSERVE")
    void decide_marginalDifference_returnsObserve() {
        MetricsCollector collector = Mockito.mock(MetricsCollector.class);
        when(collector.getVersionSnapshots("ling1"))
                .thenReturn(mapOf(
                        snapshot(0.005, 100, 1000),
                        snapshot(0.006, 110, 1000)));  // 错误率略高但未达 2x，p99 略高但未达 1.5x

        CanaryDecisionService service = new CanaryDecisionService(collector);
        CanaryDecisionDTO result = service.decide("ling1");

        assertEquals("OBSERVE", result.getRecommendation());
        assertTrue(result.isSufficientData());
    }

    @Test
    @DisplayName("缺少版本快照时返回 OBSERVE 且 sufficientData=false")
    void decide_missingSnapshot_returnsObserve() {
        MetricsCollector collector = Mockito.mock(MetricsCollector.class);
        when(collector.getVersionSnapshots("ling1"))
                .thenReturn(mapOf(snapshot(0.001, 100, 1000)));

        CanaryDecisionService service = new CanaryDecisionService(collector);
        CanaryDecisionDTO result = service.decide("ling1");

        assertEquals("OBSERVE", result.getRecommendation());
        assertFalse(result.isSufficientData());
    }

    @Test
    @DisplayName("错误率波动超过0.5%时即使指标持平也应返回 OBSERVE")
    void decide_errorRateFluctuation_returnsObserve() {
        MetricsCollector collector = Mockito.mock(MetricsCollector.class);
        // 稳定版错误率固定 1%，金丝雀版错误率波动
        when(collector.getVersionSnapshots("ling1"))
                .thenReturn(mapOf(
                        snapshot(0.01, 150, 1000),
                        snapshot(0.005, 140, 1000)));

        CanaryDecisionService service = new CanaryDecisionService(collector);
        // 模拟多次调用积累历史样本，制造波动
        // 第1次：错误率 0.5%
        service.decide("ling1");
        // 第2次：错误率 1.2%（波动 0.7% > 0.5%）
        when(collector.getVersionSnapshots("ling1"))
                .thenReturn(mapOf(
                        snapshot(0.01, 150, 1000),
                        snapshot(0.012, 140, 1000)));
        service.decide("ling1");
        // 第3次：错误率 0.5%
        when(collector.getVersionSnapshots("ling1"))
                .thenReturn(mapOf(
                        snapshot(0.01, 150, 1000),
                        snapshot(0.005, 140, 1000)));
        CanaryDecisionDTO result = service.decide("ling1");

        // 波动 = 1.2% - 0.5% = 0.7% > 0.5%，应返回 OBSERVE
        assertEquals("OBSERVE", result.getRecommendation());
        assertTrue(result.getReason().contains("波动"), "应提示波动较大");
    }

    @Test
    @DisplayName("错误率波动小于0.5%且指标持平时返回 FULL_RELEASE")
    void decide_stableFluctuation_returnsFullRelease() {
        MetricsCollector collector = Mockito.mock(MetricsCollector.class);
        // 金丝雀错误率稳定在 0.5%，无波动
        when(collector.getVersionSnapshots("ling1"))
                .thenReturn(mapOf(
                        snapshot(0.01, 150, 1000),
                        snapshot(0.005, 140, 1000)));

        CanaryDecisionService service = new CanaryDecisionService(collector);
        // 积累3次稳定样本
        service.decide("ling1");
        service.decide("ling1");
        CanaryDecisionDTO result = service.decide("ling1");

        assertEquals("FULL_RELEASE", result.getRecommendation());
        assertFalse(result.getReason().contains("波动"), "不应提示波动");
    }
}
