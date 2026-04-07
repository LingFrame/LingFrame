package com.lingframe.dashboard.metrics;

import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.MetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

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

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LingMetricsMeterBridge bridge = new LingMetricsMeterBridge(
                meterRegistry, metricsCollector, governanceMetricsCollector);
        try {
            bridge.afterPropertiesSet();

            assertFalse(meterRegistry.find("lingframe.ling.health.qps").meters().isEmpty());
            assertFalse(meterRegistry.find("lingframe.ling.version.health.qps").meters().isEmpty());
            assertFalse(meterRegistry.find("lingframe.ling.governance.rate_limited_total").meters().isEmpty());
        } finally {
            bridge.destroy();
            meterRegistry.close();
        }
    }
}
