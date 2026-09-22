package com.lingframe.dashboard.metrics;

import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.ling.LingUnloadCoordinator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("灵元指标 Micrometer 桥接测试")
class LingMetricsMeterBridgeTest {

    @Test
    @DisplayName("应注册灵元健康与治理指标")
    void shouldRegisterHealthAndGovernanceMeters() throws Exception {
        MetricsCollector metricsCollector = new MetricsCollector(null);
        metricsCollector.getOrCreate("user-ling").recordSuccess(120);
        metricsCollector.getOrCreate("user-ling", "1.0.0-canary").recordFailure(250, false);

        GovernanceMetricsCollector governanceMetricsCollector = new GovernanceMetricsCollector();
        governanceMetricsCollector.recordRateLimited("user-ling", "1.0.0-canary");
        governanceMetricsCollector.recordTransactionPropagation("user-ling", "1.0.0-canary", true);
        governanceMetricsCollector.recordTransactionPropagation("user-ling", "1.0.0-canary", false);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LingMetricsMeterBridge bridge = new LingMetricsMeterBridge(
                meterRegistry, metricsCollector, governanceMetricsCollector);
        try {
            bridge.afterPropertiesSet();

            assertFalse(meterRegistry.find("lingframe.ling.health.qps").meters().isEmpty());
            assertFalse(meterRegistry.find("lingframe.ling.version.health.qps").meters().isEmpty());
            assertFalse(meterRegistry.find("lingframe.ling.governance.rate_limited_total").meters().isEmpty());
            // 新增指标：活跃灵元数 / 卸载（版本级 + 灵元级拆分）/ 事务穿透成败 / 泄漏告警数
            assertFalse(meterRegistry.find("lingframe.ling.active_count").meters().isEmpty());
            assertFalse(meterRegistry.find("lingframe.ling.unload.version_count_total").meters().isEmpty());
            assertFalse(meterRegistry.find("lingframe.ling.unload.ling_count_total").meters().isEmpty());
            assertFalse(meterRegistry.find("lingframe.ling.unload.version_last_duration_ms").meters().isEmpty());
            assertFalse(meterRegistry.find("lingframe.ling.unload.ling_last_duration_ms").meters().isEmpty());
            assertFalse(meterRegistry.find("lingframe.ling.governance.transaction_propagation_success_total").meters().isEmpty());
            assertFalse(meterRegistry.find("lingframe.ling.governance.transaction_propagation_failure_total").meters().isEmpty());
            assertFalse(meterRegistry.find("lingframe.ling.leak_alert_total").meters().isEmpty());
        } finally {
            bridge.destroy();
            meterRegistry.close();
        }
    }

    @Test
    @DisplayName("仅当未被回收时累加泄漏告警计数")
    void shouldIncrementLeakAlertOnlyWhenNotCollected() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MetricsCollector metricsCollector = new MetricsCollector(null);
        GovernanceMetricsCollector governanceMetricsCollector = new GovernanceMetricsCollector();
        EventBus eventBus = new EventBus();

        LingMetricsMeterBridge bridge = new LingMetricsMeterBridge(
                meterRegistry, metricsCollector, governanceMetricsCollector, null, null, eventBus);
        try {
            bridge.afterPropertiesSet();

            Gauge leakGauge = meterRegistry.find("lingframe.ling.leak_alert_total").gauge();
            assertNotNull(leakGauge);
            assertEquals(0.0, leakGauge.value());

            // 1. 发送正常回收事件 (collected=true)：不应累加告警
            eventBus.publish(new MonitoringEvents.LeakDetectionEvent(
                    "demo", "1.0.0", true, "normal gc"));
            awaitGaugeValue(leakGauge, 0.0, 3000, "正常回收不应触发泄漏告警计数");

            // 2. 发送泄漏事件 (collected=false)：应累加告警
            eventBus.publish(new MonitoringEvents.LeakDetectionEvent(
                    "demo", "1.0.0", false, "classloader remained alive"));
            awaitGaugeValue(leakGauge, 1.0, 3000, "检测到泄漏应触发告警计数加1");
        } finally {
            bridge.destroy();
            meterRegistry.close();
            eventBus.shutdown();
        }
    }

    /**
     * 轮询等待 Gauge 达到期望值。
     * <p>
     * LeakDetectionEvent 是异步事件（AsyncLingEvent），监听器在 EventBus dispatcher 线程执行，
     * 固定 sleep 在慢 CI 环境（GC 抖动 / 高负载）下可能不足导致偶发失败；改为轮询 + 超时断言，
     * 消除时序竞态。
     */
    private static void awaitGaugeValue(Gauge gauge, double expected, long timeoutMs, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (gauge.value() == expected) {
                return;
            }
            Thread.sleep(20);
        }
        assertEquals(expected, gauge.value(), message + "（等待超时 " + timeoutMs + "ms）");
    }

    @Test
    @DisplayName("卸载指标按粒度拆分统计（版本级与整灵元分开计数）")
    void shouldSplitUnloadMetricsByGranularity() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MetricsCollector metricsCollector = new MetricsCollector(null);
        GovernanceMetricsCollector governanceMetricsCollector = new GovernanceMetricsCollector();
        LingUnloadCoordinator unloadCoordinator = new LingUnloadCoordinator(null, null, null, null, null);

        // 2 次版本级卸载（滚动更新旧版本）+ 1 次整灵元卸载
        unloadCoordinator.onVersionUnload("ling-a", "1.0.0", null);
        unloadCoordinator.onVersionUnload("ling-a", "1.0.1", null);
        unloadCoordinator.onLingUnload("ling-b");

        LingMetricsMeterBridge bridge = new LingMetricsMeterBridge(
                meterRegistry, metricsCollector, governanceMetricsCollector, null, unloadCoordinator, null);
        try {
            bridge.afterPropertiesSet();

            assertEquals(2.0,
                    meterRegistry.find("lingframe.ling.unload.version_count_total").gauge().value(),
                    "版本级卸载应累计 2 次");
            assertEquals(1.0,
                    meterRegistry.find("lingframe.ling.unload.ling_count_total").gauge().value(),
                    "整灵元卸载应累计 1 次");
            // 耗时指标仅验证语义连通（记录值 >= 0），不精确断言耗时本身
            assertTrue(meterRegistry.find("lingframe.ling.unload.version_last_duration_ms").gauge().value() >= 0);
            assertTrue(meterRegistry.find("lingframe.ling.unload.ling_last_duration_ms").gauge().value() >= 0);
        } finally {
            bridge.destroy();
            meterRegistry.close();
            unloadCoordinator.shutdown();
        }
    }
}
