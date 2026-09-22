package com.lingframe.core.metrics;

import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.LingMetricsCollector;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class MetricsCollector implements LingMetricsCollector {
    private final LingRepository lingRepository;
    private final Map<String, LingHealthMetrics> metricsMap = new ConcurrentHashMap<>();
    private final Map<String, LingHealthMetrics> versionMetricsMap = new ConcurrentHashMap<>();
    
    public MetricsCollector(LingRepository lingRepository) {
        this.lingRepository = lingRepository;
    }
    
    public LingHealthMetrics getOrCreate(String lingId) {
        return metricsMap.computeIfAbsent(lingId, LingHealthMetrics::new);
    }
    
    public LingHealthMetrics get(String lingId) {
        return metricsMap.get(lingId);
    }

    public LingHealthMetrics getOrCreate(String lingId, String version) {
        if (version == null || version.isEmpty()) {
            return getOrCreate(lingId);
        }
        return versionMetricsMap.computeIfAbsent(versionKey(lingId, version), key -> {
            LingHealthMetrics metrics = new LingHealthMetrics(lingId);
            metrics.setVersion(version);
            return metrics;
        });
    }
    
    public MetricsSnapshot getSnapshot(String lingId) {
        LingHealthMetrics metrics = metricsMap.get(lingId);
        if (metrics != null) {
            return metrics.snapshot();
        }
        Map<String, MetricsSnapshot> versionSnapshots = getVersionSnapshots(lingId);
        if (!versionSnapshots.isEmpty()) {
            return aggregateSnapshots(lingId, versionSnapshots.values());
        }
        return MetricsSnapshot.empty(lingId);
    }
    
    public List<MetricsSnapshot> getAllSnapshots() {
        return metricsMap.keySet().stream()
                .map(this::getSnapshot)
                .collect(Collectors.toList());
    }
    
    public void updateVersion(String lingId, String version) {
        LingHealthMetrics metrics = getOrCreate(lingId);
        metrics.setVersion(version);
    }

    public Map<String, MetricsSnapshot> getVersionSnapshots(String lingId) {
        return versionMetricsMap.entrySet().stream()
                .filter(entry -> lingId.equals(extractLingId(entry.getKey())))
                .collect(Collectors.toMap(
                        entry -> entry.getValue().getVersion(),
                        entry -> entry.getValue().snapshot(),
                        (existing, replacement) -> replacement,
                        LinkedHashMap::new
                ));
    }

    public Map<String, Map<String, MetricsSnapshot>> getAllVersionSnapshots() {
        return versionMetricsMap.values().stream()
                .map(LingHealthMetrics::snapshot)
                .collect(Collectors.groupingBy(
                        MetricsSnapshot::getLingId,
                        LinkedHashMap::new,
                        Collectors.toMap(
                                MetricsSnapshot::getVersion,
                                snapshot -> snapshot,
                                (existing, replacement) -> replacement,
                                LinkedHashMap::new
                        )
                ));
    }
    
    public void reset(String lingId) {
        LingHealthMetrics metrics = metricsMap.get(lingId);
        if (metrics != null) {
            metrics.reset();
        }
        versionMetricsMap.forEach((key, value) -> {
            if (lingId.equals(extractLingId(key))) {
                value.reset();
            }
        });
    }

    public void resetAll() {
        metricsMap.values().forEach(LingHealthMetrics::reset);
        versionMetricsMap.values().forEach(LingHealthMetrics::reset);
    }

    public void remove(String lingId) {
        metricsMap.remove(lingId);
        versionMetricsMap.keySet().removeIf(key -> lingId.equals(extractLingId(key)));
        log.info("[Metrics] Removed metrics for ling: {}", lingId);
    }
    
    public void syncWithRuntime() {
        if (lingRepository == null) {
            return;
        }
        
        List<String> activeLingIds = lingRepository.getAllRuntimes().stream()
                .map(LingRuntime::getLingId)
                .collect(Collectors.toList());
        
        metricsMap.keySet().retainAll(activeLingIds);
        versionMetricsMap.keySet().removeIf(key -> !activeLingIds.contains(extractLingId(key)));
        
        for (String lingId : activeLingIds) {
            getOrCreate(lingId);
        }
    }
    
    public Map<String, LingHealthMetrics> getAllMetrics() {
        return new ConcurrentHashMap<>(metricsMap);
    }

    private String versionKey(String lingId, String version) {
        return lingId + "::" + version;
    }

    private String extractLingId(String key) {
        int separator = key.indexOf("::");
        return separator >= 0 ? key.substring(0, separator) : key;
    }

    private MetricsSnapshot aggregateSnapshots(String lingId, Collection<MetricsSnapshot> snapshots) {
        MetricsSnapshot aggregated = MetricsSnapshot.empty(lingId);
        if (snapshots == null || snapshots.isEmpty()) {
            return aggregated;
        }

        long timestamp = 0L;
        long totalRequests = 0L;
        long successRequests = 0L;
        long failedRequests = 0L;
        long timeoutRequests = 0L;
        long activeRequests = 0L;
        long maxLatencyMs = 0L;
        long windowDurationMs = Long.MAX_VALUE;
        double totalLatency = 0.0;
        // 注意：QPS 不直接相加（各快照可能基于不同窗口时长计算，直接求和语义错误），
        // 改为在最后基于总请求数和聚合窗口时间重新计算。

        for (MetricsSnapshot snapshot : snapshots) {
            if (snapshot == null) {
                continue;
            }
            timestamp = Math.max(timestamp, snapshot.getTimestamp());
            totalRequests += snapshot.getTotalRequests();
            successRequests += snapshot.getSuccessRequests();
            failedRequests += snapshot.getFailedRequests();
            timeoutRequests += snapshot.getTimeoutRequests();
            activeRequests += snapshot.getActiveRequests();
            maxLatencyMs = Math.max(maxLatencyMs, snapshot.getMaxLatencyMs());
            totalLatency += snapshot.getAvgLatencyMs() * snapshot.getTotalRequests();
            if (snapshot.getWindowDurationMs() > 0) {
                // 取最大窗口作为 QPS 分母：窗口越大 QPS 越小，更保守准确，
                // 避免取最小窗口高估聚合 QPS（总请求横跨各版本窗口，最小窗口无法覆盖全部请求时长）
                windowDurationMs = Math.max(windowDurationMs, snapshot.getWindowDurationMs());
            }
        }

        double avgLatencyMs = totalRequests > 0 ? totalLatency / totalRequests : 0.0;
        double successRate = totalRequests > 0 ? (successRequests * 100.0 / totalRequests) : 100.0;
        double errorRate = totalRequests > 0 ? (failedRequests * 100.0 / totalRequests) : 0.0;
        double timeoutRate = totalRequests > 0 ? (timeoutRequests * 100.0 / totalRequests) : 0.0;
        long effectiveWindowMs = windowDurationMs == Long.MAX_VALUE ? 0L : windowDurationMs;
        // 基于总请求数和聚合窗口时间重新计算 QPS，避免直接相加不同窗口时长的 QPS
        double qps = (effectiveWindowMs > 0 && totalRequests > 0)
                ? totalRequests * 1000.0 / effectiveWindowMs
                : 0.0;

        aggregated.setTimestamp(timestamp > 0 ? timestamp : System.currentTimeMillis());
        aggregated.setTotalRequests(totalRequests);
        aggregated.setSuccessRequests(successRequests);
        aggregated.setFailedRequests(failedRequests);
        aggregated.setTimeoutRequests(timeoutRequests);
        aggregated.setSuccessRate(successRate);
        aggregated.setErrorRate(errorRate);
        aggregated.setTimeoutRate(timeoutRate);
        aggregated.setAvgLatencyMs(avgLatencyMs);
        aggregated.setMaxLatencyMs(maxLatencyMs);
        aggregated.setQps(qps);
        aggregated.setActiveRequests(activeRequests);
        aggregated.setWindowDurationMs(effectiveWindowMs);
        aggregated.setHealthStatus(determineOverallHealth(snapshots));
        return aggregated;
    }

    private MetricsSnapshot.HealthStatus determineOverallHealth(Collection<MetricsSnapshot> snapshots) {
        boolean hasWarning = false;
        for (MetricsSnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.getHealthStatus() == null) {
                continue;
            }
            if (snapshot.getHealthStatus() == MetricsSnapshot.HealthStatus.UNHEALTHY) {
                return MetricsSnapshot.HealthStatus.UNHEALTHY;
            }
            if (snapshot.getHealthStatus() == MetricsSnapshot.HealthStatus.WARNING) {
                hasWarning = true;
            }
        }
        return hasWarning ? MetricsSnapshot.HealthStatus.WARNING : MetricsSnapshot.HealthStatus.HEALTHY;
    }
}
