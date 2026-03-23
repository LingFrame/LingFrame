package com.lingframe.core.metrics;

import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class MetricsCollector {
    private final LingRepository lingRepository;
    private final Map<String, LingHealthMetrics> metricsMap = new ConcurrentHashMap<>();
    
    public MetricsCollector(LingRepository lingRepository) {
        this.lingRepository = lingRepository;
    }
    
    public LingHealthMetrics getOrCreate(String lingId) {
        return metricsMap.computeIfAbsent(lingId, LingHealthMetrics::new);
    }
    
    public LingHealthMetrics get(String lingId) {
        return metricsMap.get(lingId);
    }
    
    public MetricsSnapshot getSnapshot(String lingId) {
        LingHealthMetrics metrics = metricsMap.get(lingId);
        if (metrics != null) {
            return metrics.snapshot();
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
    
    public void reset(String lingId) {
        LingHealthMetrics metrics = metricsMap.get(lingId);
        if (metrics != null) {
            metrics.reset();
        }
    }
    
    public void resetAll() {
        metricsMap.values().forEach(LingHealthMetrics::reset);
    }
    
    public void remove(String lingId) {
        metricsMap.remove(lingId);
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
        
        for (String lingId : activeLingIds) {
            getOrCreate(lingId);
        }
    }
    
    public Map<String, LingHealthMetrics> getAllMetrics() {
        return new ConcurrentHashMap<>(metricsMap);
    }
}
