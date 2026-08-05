package com.lingframe.core.metrics;

import lombok.Data;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * 收集进程级整体健康快照（JVM + 系统级指标）。
     * <p>
     * 不含灵元级指标（lingMetrics 保持 null），供 Dashboard 聚合 JVM 视图使用。
     * 如需含灵元指标，使用 {@link #create(MetricsCollector)}。
     */
    public static UnifiedMetrics create() {
        UnifiedMetrics metrics = new UnifiedMetrics();
        metrics.setTimestamp(System.currentTimeMillis());
        metrics.setJvmMetrics(JVMMetrics.collect());

        SystemMetrics systemMetrics = new SystemMetrics();
        systemMetrics.setUptime(ManagementFactory.getRuntimeMXBean().getUptime());

        OperatingSystemMXBean osBean =
            ManagementFactory.getOperatingSystemMXBean();
        if (osBean.getSystemLoadAverage() >= 0) {
            systemMetrics.setSystemLoadAverage(osBean.getSystemLoadAverage());
        }
        systemMetrics.setAvailableProcessors(osBean.getAvailableProcessors());

        metrics.setSystemMetrics(systemMetrics);

        return metrics;
    }

    /**
     * 收集进程级整体健康快照（JVM + 系统 + 所有灵元指标聚合）。
     * <p>
     * 供 Dashboard 暴露「灵核整体运行时画像」端点使用，对应手册第 2.1 条「长期运行可观测」。
     *
     * @param metricsCollector 灵元指标收集器，为 null 时 lingMetrics 保持 null
     */
    public static UnifiedMetrics create(MetricsCollector metricsCollector) {
        UnifiedMetrics metrics = create();
        if (metricsCollector != null) {
            List<MetricsSnapshot> snapshots = metricsCollector.getAllSnapshots();
            if (snapshots != null && !snapshots.isEmpty()) {
                Map<String, MetricsSnapshot> lingMap = new LinkedHashMap<>();
                for (MetricsSnapshot snapshot : snapshots) {
                    if (snapshot != null && snapshot.getLingId() != null) {
                        lingMap.put(snapshot.getLingId(), snapshot);
                    }
                }
                metrics.setLingMetrics(lingMap);
            }
        }
        return metrics;
    }
}
