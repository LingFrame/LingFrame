package com.lingframe.core.metrics;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
@Getter
public class LingHealthMetrics {
    private final String lingId;
    private volatile String version;
    private volatile long windowStartTime;
    
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder successRequests = new LongAdder();
    private final LongAdder failedRequests = new LongAdder();
    private final LongAdder timeoutRequests = new LongAdder();
    
    private final LongAdder totalLatencyMs = new LongAdder();
    private final AtomicLong maxLatencyMs = new AtomicLong(0);
    private final LatencyHistogram latencyHistogram = new LatencyHistogram();
    
    private final LongAdder requestsInWindow = new LongAdder();
    
    private final AtomicLong activeRequests = new AtomicLong(0);
    
    private final Map<String, Object> customMetrics = new ConcurrentHashMap<>();
    
    public LingHealthMetrics(String lingId) {
        this.lingId = lingId;
        this.windowStartTime = System.currentTimeMillis();
    }
    
    public void setVersion(String version) {
        this.version = version;
    }
    
    public void recordSuccess(long latencyMs) {
        totalRequests.add(1);
        successRequests.add(1);
        totalLatencyMs.add(latencyMs);
        latencyHistogram.record(latencyMs);
        requestsInWindow.add(1);
        
        updateMaxLatency(latencyMs);
    }
    
    public void recordFailure(long latencyMs, boolean isTimeout) {
        totalRequests.add(1);
        failedRequests.add(1);
        totalLatencyMs.add(latencyMs);
        latencyHistogram.record(latencyMs);
        requestsInWindow.add(1);
        
        if (isTimeout) {
            timeoutRequests.add(1);
        }
        
        updateMaxLatency(latencyMs);
    }
    
    private void updateMaxLatency(long latencyMs) {
        long current;
        do {
            current = maxLatencyMs.get();
            if (latencyMs <= current) {
                break;
            }
        } while (!maxLatencyMs.compareAndSet(current, latencyMs));
    }
    
    public void startRequest() {
        activeRequests.incrementAndGet();
    }
    
    public void endRequest() {
        activeRequests.decrementAndGet();
    }
    
    public MetricsSnapshot snapshot() {
        long now = System.currentTimeMillis();
        long windowDuration = now - windowStartTime;
        
        long total = totalRequests.sum();
        long success = successRequests.sum();
        long failed = failedRequests.sum();
        long timeout = timeoutRequests.sum();
        
        double successRate = total > 0 ? (success * 100.0 / total) : 100.0;
        double errorRate = total > 0 ? (failed * 100.0 / total) : 0.0;
        double timeoutRate = total > 0 ? (timeout * 100.0 / total) : 0.0;
        
        double avgLatency = total > 0 ? (totalLatencyMs.sum() * 1.0 / total) : 0.0;
        double qps = windowDuration > 0 ? (requestsInWindow.sum() * 1000.0 / windowDuration) : 0.0;
        
        MetricsSnapshot snapshot = new MetricsSnapshot();
        snapshot.setLingId(lingId);
        snapshot.setVersion(version);
        snapshot.setTimestamp(now);
        
        snapshot.setTotalRequests(total);
        snapshot.setSuccessRequests(success);
        snapshot.setFailedRequests(failed);
        snapshot.setTimeoutRequests(timeout);
        
        snapshot.setSuccessRate(successRate);
        snapshot.setErrorRate(errorRate);
        snapshot.setTimeoutRate(timeoutRate);
        
        snapshot.setAvgLatencyMs(avgLatency);
        snapshot.setP50LatencyMs(latencyHistogram.getP50());
        snapshot.setP90LatencyMs(latencyHistogram.getP90());
        snapshot.setP95LatencyMs(latencyHistogram.getP95());
        snapshot.setP99LatencyMs(latencyHistogram.getP99());
        snapshot.setMaxLatencyMs(maxLatencyMs.get());
        
        snapshot.setQps(qps);
        snapshot.setActiveRequests(activeRequests.get());
        
        snapshot.setWindowDurationMs(windowDuration);
        
        snapshot.setHealthStatus(determineHealthStatus(errorRate, avgLatency));
        
        snapshot.setCustomMetrics(new ConcurrentHashMap<>(customMetrics));
        
        return snapshot;
    }
    
    private MetricsSnapshot.HealthStatus determineHealthStatus(double errorRate, double avgLatency) {
        if (errorRate > 5.0 || avgLatency > 1000) {
            return MetricsSnapshot.HealthStatus.UNHEALTHY;
        } else if (errorRate > 1.0 || avgLatency > 500) {
            return MetricsSnapshot.HealthStatus.WARNING;
        } else {
            return MetricsSnapshot.HealthStatus.HEALTHY;
        }
    }
    
    public void reset() {
        totalRequests.reset();
        successRequests.reset();
        failedRequests.reset();
        timeoutRequests.reset();
        totalLatencyMs.reset();
        maxLatencyMs.set(0);
        latencyHistogram.reset();
        requestsInWindow.reset();
        windowStartTime = System.currentTimeMillis();
        customMetrics.clear();
    }
    
    public void putCustomMetric(String key, Object value) {
        customMetrics.put(key, value);
    }
}
