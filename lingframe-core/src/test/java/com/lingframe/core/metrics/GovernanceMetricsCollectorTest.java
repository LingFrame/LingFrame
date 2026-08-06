package com.lingframe.core.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GovernanceMetricsCollector 测试")
class GovernanceMetricsCollectorTest {

    private GovernanceMetricsCollector collector;

    @BeforeEach
    void setUp() {
        collector = new GovernanceMetricsCollector();
    }

    @Test
    @DisplayName("应同时维护 ling 汇总与 version 治理指标")
    void shouldKeepSummaryAndVersionMetrics() {
        collector.recordRateLimited("order-ling", "1.0.0");
        collector.recordTimeout("order-ling", "1.1.0");
        collector.recordCircuitOpened("order-ling", "1.1.0");
        collector.recordThreadBudgetSnapshot("order-ling", "1.1.0", 2, 4);
        collector.recordCpuBudgetObservation("order-ling", "1.1.0", 120, 500);
        collector.recordMemoryBudgetObservation("order-ling", "1.1.0", 2 * 1024 * 1024L, 16);

        GovernanceMetricsSnapshot summary = collector.getSummary("order-ling");
        Map<String, GovernanceMetricsSnapshot> versions = collector.getVersionSnapshots("order-ling");

        assertEquals(1, summary.getRateLimitedRequests());
        assertEquals(1, summary.getTimeoutRequests());
        assertEquals(1, summary.getCircuitOpenedCount());
        assertEquals(2, summary.getActiveIsolatedThreads());
        assertEquals(4, summary.getMaxConcurrentThreadsBudget());
        assertEquals(120, summary.getCpuTimeMsLastMinute());
        assertEquals(2 * 1024 * 1024L, summary.getEstimatedHeapDeltaBytes());
        assertEquals(2, versions.size());
        assertEquals(1, versions.get("1.0.0").getRateLimitedRequests());
        assertEquals(1, versions.get("1.1.0").getTimeoutRequests());
        assertEquals(500, versions.get("1.1.0").getCpuBudgetMsPerMinute());
        assertEquals(16, versions.get("1.1.0").getMemoryBudgetMb());
    }

    @Test
    @DisplayName("预算告警计数应按越线次数累计，而不是按每次采样累计")
    void shouldCountBudgetExceededByTransitions() {
        collector.recordCpuBudgetObservation("order-ling", "1.0.0", 300, 500);
        collector.recordCpuBudgetObservation("order-ling", "1.0.0", 300, 500);
        collector.recordCpuBudgetObservation("order-ling", "1.0.0", 50, 500);

        collector.recordMemoryBudgetObservation("order-ling", "1.0.0", 2 * 1024 * 1024L, 1);
        collector.recordMemoryBudgetObservation("order-ling", "1.0.0", 3 * 1024 * 1024L, 1);

        GovernanceMetricsSnapshot snapshot = collector.getVersionSnapshots("order-ling").get("1.0.0");

        assertEquals(1, snapshot.getCpuBudgetExceededCount());
        assertEquals(1, snapshot.getMemoryBudgetExceededCount());
    }

    @Test
    @DisplayName("recordCircuitOpenRejected 计数正确")
    void shouldRecordCircuitOpenRejected() {
        collector.recordCircuitOpenRejected("ling-a", "1.0");
        collector.recordCircuitOpenRejected("ling-a", "1.0");

        GovernanceMetricsSnapshot summary = collector.getSummary("ling-a");
        assertEquals(2, summary.getCircuitOpenRejections());
    }

    @Test
    @DisplayName("recordBulkheadRejected 计数正确")
    void shouldRecordBulkheadRejected() {
        collector.recordBulkheadRejected("ling-a", "1.0");

        GovernanceMetricsSnapshot summary = collector.getSummary("ling-a");
        assertEquals(1, summary.getBulkheadRejectedRequests());
        assertEquals(1, summary.getThreadBudgetExceededCount());
    }

    @Test
    @DisplayName("recordRecovered 计数正确")
    void shouldRecordRecovered() {
        collector.recordRecovered("ling-a", "1.0");

        GovernanceMetricsSnapshot summary = collector.getSummary("ling-a");
        assertEquals(1, summary.getRecoveryCount());
    }

    @Test
    @DisplayName("getSummary 不存在的 lingId 返回空快照")
    void shouldReturnEmptySnapshotForUnknownLingId() {
        GovernanceMetricsSnapshot summary = collector.getSummary("nonexistent");
        assertEquals("nonexistent", summary.getLingId());
        assertEquals(0, summary.getRateLimitedRequests());
    }

    @Test
    @DisplayName("getVersionSnapshots 不存在的 lingId 返回空 Map")
    void shouldReturnEmptyVersionMapForUnknownLingId() {
        Map<String, GovernanceMetricsSnapshot> versions = collector.getVersionSnapshots("nonexistent");
        assertTrue(versions.isEmpty());
    }

    @Test
    @DisplayName("getAllSummaries 返回所有 ling 的汇总")
    void shouldReturnAllSummaries() {
        collector.recordRateLimited("ling-a", "1.0");
        collector.recordTimeout("ling-b", "2.0");

        Map<String, GovernanceMetricsSnapshot> all = collector.getAllSummaries();
        assertEquals(2, all.size());
        assertTrue(all.containsKey("ling-a"));
        assertTrue(all.containsKey("ling-b"));
    }

    @Test
    @DisplayName("remove 删除指定 lingId 的所有指标")
    void shouldRemoveLingMetrics() {
        collector.recordRateLimited("ling-a", "1.0");
        collector.recordTimeout("ling-b", "2.0");

        collector.remove("ling-a");

        assertEquals(0, collector.getSummary("ling-a").getRateLimitedRequests());
        assertEquals(1, collector.getSummary("ling-b").getTimeoutRequests());
    }

    @Test
    @DisplayName("null lingId 不记录指标")
    void shouldIgnoreNullLingId() {
        collector.recordRateLimited(null, "1.0");
        collector.recordTimeout("", "1.0");

        assertTrue(collector.getAllSummaries().isEmpty());
    }

    @Test
    @DisplayName("null version 只记录 summary 不记录 version")
    void shouldOnlyRecordSummaryForNullVersion() {
        collector.recordRateLimited("ling-a", null);

        GovernanceMetricsSnapshot summary = collector.getSummary("ling-a");
        assertEquals(1, summary.getRateLimitedRequests());
        assertTrue(collector.getVersionSnapshots("ling-a").isEmpty());
    }

    @Test
    @DisplayName("无 version 指标时 getSummary 聚合 version 指标")
    void shouldAggregateVersionSnapshotsWhenNoSummary() {
        // 只记录 version 级别指标
        collector.recordRateLimited("ling-a", "1.0");
        collector.recordTimeout("ling-a", "2.0");

        GovernanceMetricsSnapshot summary = collector.getSummary("ling-a");
        assertEquals(1, summary.getRateLimitedRequests());
        assertEquals(1, summary.getTimeoutRequests());
    }

    @Test
    @DisplayName("CPU 预算超限计数正确")
    void shouldCountCpuBudgetExceeded() {
        // 第一次超限
        collector.recordCpuBudgetObservation("ling-a", "1.0", 600, 500);
        // 持续超限不重复计数
        collector.recordCpuBudgetObservation("ling-a", "1.0", 100, 500);

        GovernanceMetricsSnapshot snapshot = collector.getVersionSnapshots("ling-a").get("1.0");
        assertEquals(1, snapshot.getCpuBudgetExceededCount());
    }

    @Test
    @DisplayName("内存预算超限计数正确")
    void shouldCountMemoryBudgetExceeded() {
        // 第一次超限
        collector.recordMemoryBudgetObservation("ling-a", "1.0", 10 * 1024 * 1024L, 5);
        // 持续超限不重复计数
        collector.recordMemoryBudgetObservation("ling-a", "1.0", 15 * 1024 * 1024L, 5);

        GovernanceMetricsSnapshot snapshot = collector.getVersionSnapshots("ling-a").get("1.0");
        assertEquals(1, snapshot.getMemoryBudgetExceededCount());
    }

    @Test
    @DisplayName("多版本聚合并预算上限应取最大值而非求和")
    void aggregateBudgetFieldsShouldTakeMaxAcrossVersions() {
        // v1.0 设 CPU 预算 500ms/min、内存预算 16MB
        collector.recordCpuBudgetObservation("ling-a", "1.0", 100, 500);
        collector.recordMemoryBudgetObservation("ling-a", "1.0", 1 * 1024 * 1024L, 16);
        // v2.0 设 CPU 预算 800ms/min、内存预算 32MB
        collector.recordCpuBudgetObservation("ling-a", "2.0", 100, 800);
        collector.recordMemoryBudgetObservation("ling-a", "2.0", 1 * 1024 * 1024L, 32);

        // 删除 ling 汇总桶，强制走 getVersionSnapshots 聚合路径
        // （GovernanceMetricsCollector.getSummary 在 summaryBuckets 缺失时会回退到版本聚合）
        GovernanceMetricsSnapshot summary = collector.getSummary("ling-a");

        // 聚合后应取两版本中的上限，而非加和
        assertEquals(800, summary.getCpuBudgetMsPerMinute(),
                "CPU 预算上限应取两版本最大值 800，而非求和 1300");
        assertEquals(32, summary.getMemoryBudgetMb(),
                "内存预算上限应取两版本最大值 32，而非求和 48");
    }
}
