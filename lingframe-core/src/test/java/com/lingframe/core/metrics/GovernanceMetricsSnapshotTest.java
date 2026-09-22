package com.lingframe.core.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GovernanceMetricsSnapshot 测试")
class GovernanceMetricsSnapshotTest {

    @Test
    @DisplayName("empty 创建空快照")
    void shouldCreateEmptySnapshot() {
        GovernanceMetricsSnapshot snapshot = GovernanceMetricsSnapshot.empty("ling-a");

        assertEquals("ling-a", snapshot.getLingId());
        assertTrue(snapshot.getTimestamp() > 0);
        assertEquals(0, snapshot.getRateLimitedRequests());
        assertEquals(0, snapshot.getTimeoutRequests());
        assertEquals(0, snapshot.getCircuitOpenRejections());
    }

    @Test
    @DisplayName("setter/getter 正常工作")
    void shouldSetAndGetValues() {
        GovernanceMetricsSnapshot snapshot = new GovernanceMetricsSnapshot();
        snapshot.setLingId("ling-b");
        snapshot.setVersion("1.0.0");
        snapshot.setRateLimitedRequests(10);
        snapshot.setTimeoutRequests(5);
        snapshot.setCircuitOpenRejections(3);
        snapshot.setCircuitOpenedCount(2);
        snapshot.setBulkheadRejectedRequests(1);
        snapshot.setRecoveryCount(4);
        snapshot.setActiveIsolatedThreads(8);
        snapshot.setMaxConcurrentThreadsBudget(16);
        snapshot.setThreadBudgetExceededCount(2);
        snapshot.setCpuTimeMsLastMinute(500);
        snapshot.setCpuBudgetMsPerMinute(1000);
        snapshot.setCpuBudgetExceededCount(1);
        snapshot.setEstimatedHeapDeltaBytes(1024);
        snapshot.setMemoryBudgetMb(256);
        snapshot.setMemoryBudgetExceededCount(0);

        assertEquals("ling-b", snapshot.getLingId());
        assertEquals("1.0.0", snapshot.getVersion());
        assertEquals(10, snapshot.getRateLimitedRequests());
        assertEquals(5, snapshot.getTimeoutRequests());
        assertEquals(3, snapshot.getCircuitOpenRejections());
        assertEquals(2, snapshot.getCircuitOpenedCount());
        assertEquals(1, snapshot.getBulkheadRejectedRequests());
        assertEquals(4, snapshot.getRecoveryCount());
        assertEquals(8, snapshot.getActiveIsolatedThreads());
        assertEquals(16, snapshot.getMaxConcurrentThreadsBudget());
        assertEquals(2, snapshot.getThreadBudgetExceededCount());
        assertEquals(500, snapshot.getCpuTimeMsLastMinute());
        assertEquals(Integer.valueOf(1000), snapshot.getCpuBudgetMsPerMinute());
        assertEquals(1, snapshot.getCpuBudgetExceededCount());
        assertEquals(1024, snapshot.getEstimatedHeapDeltaBytes());
        assertEquals(Integer.valueOf(256), snapshot.getMemoryBudgetMb());
        assertEquals(0, snapshot.getMemoryBudgetExceededCount());
    }
}
