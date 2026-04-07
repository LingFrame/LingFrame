package com.lingframe.core.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("MetricsCollector 测试")
class MetricsCollectorTest {

    @Test
    @DisplayName("应同时维护 ling 汇总指标与 version 维度指标")
    void shouldKeepLingSummaryAndVersionMetrics() {
        MetricsCollector collector = new MetricsCollector(null);

        collector.getOrCreate("order-ling").recordSuccess(100);
        collector.getOrCreate("order-ling").recordFailure(200, false);

        collector.getOrCreate("order-ling", "1.0.0").recordSuccess(100);
        collector.getOrCreate("order-ling", "1.1.0").recordFailure(200, true);

        MetricsSnapshot summary = collector.getSnapshot("order-ling");
        Map<String, MetricsSnapshot> versions = collector.getVersionSnapshots("order-ling");

        assertEquals(2, summary.getTotalRequests());
        assertEquals(2, versions.size());
        assertNotNull(versions.get("1.0.0"));
        assertNotNull(versions.get("1.1.0"));
        assertEquals(1, versions.get("1.0.0").getSuccessRequests());
        assertEquals(1, versions.get("1.1.0").getFailedRequests());
        assertEquals(1, versions.get("1.1.0").getTimeoutRequests());
    }
}
