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
    private long recoveryCount;
    private long timestamp;

    public static GovernanceMetricsSnapshot empty(String lingId) {
        GovernanceMetricsSnapshot snapshot = new GovernanceMetricsSnapshot();
        snapshot.setLingId(lingId);
        snapshot.setTimestamp(System.currentTimeMillis());
        return snapshot;
    }
}
