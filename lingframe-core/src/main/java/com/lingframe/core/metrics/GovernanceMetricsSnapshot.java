package com.lingframe.core.metrics;

import lombok.Data;

@Data
public class GovernanceMetricsSnapshot {
    private String lingId;
    private String version;
    private long rateLimitedRequests;
    private long timeoutRequests;
    private long circuitOpenRejections;
    private long circuitOpenedCount;
    private long bulkheadRejectedRequests;
    private long forceDrainCount;
    private long drainTimeoutAbortCount;
    private long recoveryCount;
    private long connectionPoisonedCount;
    private int activeIsolatedThreads;
    private int maxConcurrentThreadsBudget;
    private long threadBudgetExceededCount;
    private long cpuTimeMsLastMinute;
    private Integer cpuBudgetMsPerMinute;
    private long cpuBudgetExceededCount;
    private long estimatedHeapDeltaBytes;
    private Integer memoryBudgetMb;
    private long memoryBudgetExceededCount;
    private long timestamp;

    public static GovernanceMetricsSnapshot empty(String lingId) {
        GovernanceMetricsSnapshot snapshot = new GovernanceMetricsSnapshot();
        snapshot.setLingId(lingId);
        snapshot.setTimestamp(System.currentTimeMillis());
        return snapshot;
    }
}
