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
            map.put("v" + i, snapshots[i]);
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
}
