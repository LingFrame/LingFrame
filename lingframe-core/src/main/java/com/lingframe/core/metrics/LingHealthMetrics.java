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
    /**
     * 健康判定滑动窗口长度（毫秒）。超过该时长后快照会自动 rollover（重置计数），
     * 避免历史瞬时限流/错误爆发永久钉死健康状态，使健康判定始终基于最近窗口。
     */
    private static final long HEALTH_WINDOW_MS = 60_000L;

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
    // 治理/准入拒绝（限流、熔断、舱壁、状态拒绝、权限拒绝、路由失败）单独计数：
    // 不计入健康错误率，否则高并发限流会误杀健康实例。
    private final LongAdder governanceRejectedRequests = new LongAdder();
    
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

    /**
     * 记录一次「治理/准入拒绝」（限流、熔断、舱壁、状态拒绝、权限拒绝、路由失败）。
     *
     * <p>此类请求是平台层在业务执行前主动拦截，不反映实例真实健康度，因此：
     * <ul>
     *   <li>计入 {@code totalRequests} / {@code requestsInWindow}（贡献 QPS 与错误率分母）；</li>
     *   <li><b>不</b>计入 {@code failedRequests}，从而不参与健康 {@code errorRate}；</li>
     *   <li>单独计入 {@code governanceRejectedRequests}，供可观测性区分。</li>
     * </ul>
     */
    public void recordGovernanceRejection(long latencyMs) {
        totalRequests.add(1);
        requestsInWindow.add(1);
        totalLatencyMs.add(latencyMs);
        latencyHistogram.record(latencyMs);
        governanceRejectedRequests.increment();
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
        rolloverIfNeeded(now);
        long windowDuration = now - windowStartTime;

        long total = totalRequests.sum();
        long success = successRequests.sum();
        long failed = failedRequests.sum();
        long timeout = timeoutRequests.sum();

        double successRate = total > 0 ? (success * 100.0 / total) : 100.0;
        double errorRate = total > 0 ? (failed * 100.0 / total) : 0.0;
        double timeoutRate = total > 0 ? (timeout * 100.0 / total) : 0.0;
        long govRejected = governanceRejectedRequests.sum();
        double governanceRejectedRate = total > 0 ? (govRejected * 100.0 / total) : 0.0;
        
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
        snapshot.setGovernanceRejectedRequests(govRejected);

        snapshot.setSuccessRate(successRate);
        snapshot.setErrorRate(errorRate);
        snapshot.setTimeoutRate(timeoutRate);
        snapshot.setGovernanceRejectedRate(governanceRejectedRate);
        
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
        governanceRejectedRequests.reset();
        windowStartTime = System.currentTimeMillis();
        customMetrics.clear();
    }

    /**
     * 滑动窗口老化：若距上次窗口起点已超过 {@link #HEALTH_WINDOW_MS}，重置全部计数，
     * 使健康判定始终基于最近窗口，避免历史瞬时限流/错误爆发永久污染 errorRate。
     *
     * <p>使用双重检查 + 同步块，保证在多快照并发下至多发生一次 reset（至多丢失一次窗口数据），
     * 不会破坏计数正确性。
     */
    private void rolloverIfNeeded(long now) {
        if (now - windowStartTime < HEALTH_WINDOW_MS) {
            return;
        }
        synchronized (this) {
            if (now - windowStartTime >= HEALTH_WINDOW_MS) {
                reset();
            }
        }
    }
    
    public void putCustomMetric(String key, Object value) {
        customMetrics.put(key, value);
    }
}
