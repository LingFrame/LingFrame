package com.lingframe.core.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("GovernanceMetricsCollector 测试")
class GovernanceMetricsCollectorTest {

    @Test
    @DisplayName("应同时维护 ling 汇总与 version 治理指标")
    void shouldKeepSummaryAndVersionMetrics() {
        GovernanceMetricsCollector collector = new GovernanceMetricsCollector();

        collector.recordRateLimited("order-ling", "1.0.0");
        collector.recordTimeout("order-ling", "1.1.0");
        collector.recordCircuitOpened("order-ling", "1.1.0");

        GovernanceMetricsSnapshot summary = collector.getSummary("order-ling");
        Map<String, GovernanceMetricsSnapshot> versions = collector.getVersionSnapshots("order-ling");

        assertEquals(1, summary.getRateLimitedRequests());
        assertEquals(1, summary.getTimeoutRequests());
        assertEquals(1, summary.getCircuitOpenedCount());
        assertEquals(2, versions.size());
        assertEquals(1, versions.get("1.0.0").getRateLimitedRequests());
        assertEquals(1, versions.get("1.1.0").getTimeoutRequests());
    }
}
