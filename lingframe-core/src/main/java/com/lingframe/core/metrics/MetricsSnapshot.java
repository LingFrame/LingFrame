package com.lingframe.core.metrics;

import lombok.Data;

import java.util.Map;

@Data
public class MetricsSnapshot {
    private String lingId;
    private String version;
    private long timestamp;
    
    private long totalRequests;
    private long successRequests;
    private long failedRequests;
    private long timeoutRequests;
    
    private double successRate;
    private double errorRate;
    private double timeoutRate;
    
    private double avgLatencyMs;
    private long p50LatencyMs;
    private long p90LatencyMs;
    private long p95LatencyMs;
    private long p99LatencyMs;
    private long maxLatencyMs;
    
    private double qps;
    private long activeRequests;
    
    private HealthStatus healthStatus;
    
    private long windowDurationMs;
    
    private Map<String, Object> customMetrics;
    
    public enum HealthStatus {
        HEALTHY,
        WARNING,
        UNHEALTHY,
        UNKNOWN
    }
    
    public static MetricsSnapshot empty(String lingId) {
        MetricsSnapshot snapshot = new MetricsSnapshot();
        snapshot.setLingId(lingId);
        snapshot.setTimestamp(System.currentTimeMillis());
        snapshot.setHealthStatus(HealthStatus.UNKNOWN);
        return snapshot;
    }
}
