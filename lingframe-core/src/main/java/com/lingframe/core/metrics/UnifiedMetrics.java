package com.lingframe.core.metrics;

import lombok.Data;
import java.lang.management.ManagementFactory;
import java.util.Map;

@Data
public class UnifiedMetrics {
    private long timestamp;
    
    private JVMMetrics jvmMetrics;
    
    private Map<String, MetricsSnapshot> lingMetrics;
    
    private SystemMetrics systemMetrics;
    
    @Data
    public static class SystemMetrics {
        private long uptime;
        private double systemLoadAverage;
        private int availableProcessors;
    }
    
    public static UnifiedMetrics create() {
        UnifiedMetrics metrics = new UnifiedMetrics();
        metrics.setTimestamp(System.currentTimeMillis());
        metrics.setJvmMetrics(JVMMetrics.collect());
        
        SystemMetrics systemMetrics = new SystemMetrics();
        systemMetrics.setUptime(ManagementFactory.getRuntimeMXBean().getUptime());
        
        java.lang.management.OperatingSystemMXBean osBean = 
            java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        if (osBean.getSystemLoadAverage() >= 0) {
            systemMetrics.setSystemLoadAverage(osBean.getSystemLoadAverage());
        }
        systemMetrics.setAvailableProcessors(osBean.getAvailableProcessors());
        
        metrics.setSystemMetrics(systemMetrics);
        
        return metrics;
    }
}
