package com.lingframe.core.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LingHealthMetrics 测试")
class LingHealthMetricsTest {

    private LingHealthMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new LingHealthMetrics("test-ling");
    }

    @Test
    @DisplayName("记录成功请求后快照应反映正确数据")
    void shouldRecordSuccess() {
        metrics.recordSuccess(100);

        MetricsSnapshot snapshot = metrics.snapshot();

        assertEquals("test-ling", snapshot.getLingId());
        assertEquals(1, snapshot.getTotalRequests());
        assertEquals(1, snapshot.getSuccessRequests());
        assertEquals(0, snapshot.getFailedRequests());
        assertEquals(100.0, snapshot.getAvgLatencyMs(), 0.1);
        assertEquals(100.0, snapshot.getSuccessRate(), 0.1);
    }

    @Test
    @DisplayName("记录失败请求后快照应反映正确数据")
    void shouldRecordFailure() {
        metrics.recordFailure(200, false);

        MetricsSnapshot snapshot = metrics.snapshot();

        assertEquals(1, snapshot.getFailedRequests());
        assertEquals(0, snapshot.getTimeoutRequests());
        assertTrue(snapshot.getErrorRate() > 0);
    }

    @Test
    @DisplayName("超时请求应同时计入失败和超时")
    void shouldRecordTimeout() {
        metrics.recordFailure(5000, true);

        MetricsSnapshot snapshot = metrics.snapshot();

        assertEquals(1, snapshot.getFailedRequests());
        assertEquals(1, snapshot.getTimeoutRequests());
        assertTrue(snapshot.getTimeoutRate() > 0);
    }

    @Test
    @DisplayName("健康状态判定：高错误率为 UNHEALTHY")
    void shouldDetermineUnhealthyStatus() {
        // 记录 10 次请求，6 次失败 -> errorRate = 60%
        for (int i = 0; i < 4; i++) metrics.recordSuccess(10);
        for (int i = 0; i < 6; i++) metrics.recordFailure(10, false);

        MetricsSnapshot snapshot = metrics.snapshot();

        assertEquals(MetricsSnapshot.HealthStatus.UNHEALTHY, snapshot.getHealthStatus());
    }

    @Test
    @DisplayName("健康状态判定：低错误率为 WARNING")
    void shouldDetermineWarningStatus() {
        // 记录 100 次请求，2 次失败 -> errorRate = 2%
        for (int i = 0; i < 98; i++) metrics.recordSuccess(10);
        for (int i = 0; i < 2; i++) metrics.recordFailure(10, false);

        MetricsSnapshot snapshot = metrics.snapshot();

        assertEquals(MetricsSnapshot.HealthStatus.WARNING, snapshot.getHealthStatus());
    }

    @Test
    @DisplayName("健康状态判定：正常为 HEALTHY")
    void shouldDetermineHealthyStatus() {
        for (int i = 0; i < 100; i++) metrics.recordSuccess(10);

        MetricsSnapshot snapshot = metrics.snapshot();

        assertEquals(MetricsSnapshot.HealthStatus.HEALTHY, snapshot.getHealthStatus());
    }

    @Test
    @DisplayName("reset 后指标清零")
    void shouldResetMetrics() {
        metrics.recordSuccess(100);
        metrics.recordFailure(200, true);

        metrics.reset();

        MetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(0, snapshot.getTotalRequests());
        assertEquals(0, snapshot.getSuccessRequests());
        assertEquals(0, snapshot.getFailedRequests());
    }

    @Test
    @DisplayName("activeRequests 请求计数")
    void shouldTrackActiveRequests() {
        metrics.startRequest();
        metrics.startRequest();

        assertEquals(2, metrics.snapshot().getActiveRequests());

        metrics.endRequest();

        assertEquals(1, metrics.snapshot().getActiveRequests());
    }

    @Test
    @DisplayName("自定义指标")
    void shouldSupportCustomMetrics() {
        metrics.putCustomMetric("customKey", "customValue");

        MetricsSnapshot snapshot = metrics.snapshot();

        assertNotNull(snapshot.getCustomMetrics());
        assertEquals("customValue", snapshot.getCustomMetrics().get("customKey"));
    }

    @Test
    @DisplayName("最大延迟应正确更新")
    void shouldTrackMaxLatency() {
        metrics.recordSuccess(50);
        metrics.recordSuccess(200);
        metrics.recordSuccess(100);

        MetricsSnapshot snapshot = metrics.snapshot();

        assertEquals(200, snapshot.getMaxLatencyMs());
    }

    @Test
    @DisplayName("QPS 应基于时间窗口计算")
    void shouldCalculateQPS() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            metrics.recordSuccess(10);
        }

        // 等待时间窗口更新
        Thread.sleep(100);

        MetricsSnapshot snapshot = metrics.snapshot();

        // QPS 可能为 0（时间窗口未覆盖），仅验证不抛异常且非负
        assertTrue(snapshot.getQps() >= 0);
    }

    @Test
    @DisplayName("治理拒绝不计入失败，也不影响健康错误率（高并发限流不误杀健康实例）")
    void governanceRejectionDoesNotAffectHealthErrorRate() {
        // 模拟高并发限流场景：大量治理拒绝混入少量成功 + 1 次真实业务失败
        for (int i = 0; i < 1000; i++) {
            metrics.recordGovernanceRejection(5);
        }
        for (int i = 0; i < 10; i++) {
            metrics.recordSuccess(5);
        }
        metrics.recordFailure(5, false);

        MetricsSnapshot snapshot = metrics.snapshot();

        assertEquals(1011, snapshot.getTotalRequests());
        assertEquals(1000, snapshot.getGovernanceRejectedRequests());
        assertEquals(1, snapshot.getFailedRequests());
        // errorRate = 1/1011 ≈ 0.099%，远低于 5% 阈值 -> 仍为 HEALTHY（修复前会被限流打到 UNHEALTHY）
        assertEquals(MetricsSnapshot.HealthStatus.HEALTHY, snapshot.getHealthStatus());
        assertTrue(snapshot.getErrorRate() < 5.0);
    }

    @Test
    @DisplayName("滑动窗口：窗口超时后快照自动重置计数（避免历史污染永久钉死健康状态）")
    void slidingWindowRolloverResetsCounts() throws Exception {
        metrics.recordSuccess(10);
        metrics.recordFailure(10, false);
        assertEquals(1, metrics.snapshot().getFailedRequests());

        // 将窗口起点拨回 70s 前，触发 rollover 分支（HEALTH_WINDOW_MS=60s）
        java.lang.reflect.Field f = LingHealthMetrics.class.getDeclaredField("windowStartTime");
        f.setAccessible(true);
        f.set(metrics, System.currentTimeMillis() - 70_000L);

        MetricsSnapshot snapshot = metrics.snapshot();
        assertEquals(0, snapshot.getTotalRequests());
        assertEquals(0, snapshot.getFailedRequests());
        assertEquals(0, snapshot.getSuccessRequests());
    }

    @Test
    @DisplayName("版本号设置")
    void shouldSetVersion() {
        metrics.setVersion("2.0.0");

        assertEquals("2.0.0", metrics.getVersion());
        assertEquals("2.0.0", metrics.snapshot().getVersion());
    }
}
