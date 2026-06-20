package com.lingframe.core.metrics;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;

/**
 * JVM 性能指标采集器
 * 
 * <p>采集的指标包括：
 * <ul>
 *   <li>CPU 使用率 - 进程CPU负载和系统负载</li>
 *   <li>内存使用 - 堆内存、非堆内存、Metaspace</li>
 *   <li>类加载 - 已加载、总加载、已卸载的类数量</li>
 *   <li>线程 - 线程数、守护线程数、峰值线程数</li>
 *   <li>GC - GC 次数和总耗时</li>
 * </ul>
 */
@Slf4j
@Data
public class JVMMetrics {
    private long timestamp;
    
    private double cpuUsage;
    private double processCpuLoad;
    
    private long heapUsedMB;
    private long heapMaxMB;
    private long heapCommittedMB;
    private double heapUsagePercent;
    
    private long nonHeapUsedMB;
    private long nonHeapMaxMB;
    private double nonHeapUsagePercent;
    
    private long metaspaceUsedKB;
    private long metaspaceMaxKB;
    private long metaspaceCommittedKB;
    private double metaspaceUsagePercent;
    // Compressed Class Space 单独统计，避免与 Metaspace 混算导致数据偏大
    private long compressedClassSpaceUsedKB;
    
    private int loadedClassCount;
    private long totalLoadedClassCount;
    private long unloadedClassCount;
    
    private int threadCount;
    private int daemonThreadCount;
    private int peakThreadCount;
    
    private long gcCount;
    private long gcTimeMs;
    // 按 GC 收集器分离的统计详情
    private List<GcDetail> gcDetails;
    
    private long totalMemoryMB;
    private long freeMemoryMB;
    private long usedMemoryMB;
    private double memoryUsagePercent;
    
    private int availableProcessors;
    private double systemLoadAverage;
    
    public static JVMMetrics collect() {
        JVMMetrics metrics = new JVMMetrics();
        metrics.setTimestamp(System.currentTimeMillis());
        
        collectMemoryMetrics(metrics);
        collectClassMetrics(metrics);
        collectThreadMetrics(metrics);
        collectGCMetrics(metrics);
        collectCPUMetrics(metrics);
        
        return metrics;
    }
    
    // ================== 工具方法 ==================

    private static double percent(long used, long max) {
        if (max <= 0) return 0.0;
        return Math.round((used * 100.0 / max) * 100.0) / 100.0;
    }

    // ================== Memory ==================

    private static void collectMemoryMetrics(JVMMetrics metrics) {
        Runtime runtime = Runtime.getRuntime();

        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        metrics.setTotalMemoryMB(totalMemory / 1024 / 1024);
        metrics.setFreeMemoryMB(freeMemory / 1024 / 1024);
        metrics.setUsedMemoryMB(usedMemory / 1024 / 1024);
        metrics.setMemoryUsagePercent(percent(usedMemory, totalMemory));

        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

        // Heap
        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
        metrics.setHeapUsedMB(heap.getUsed() / 1024 / 1024);
        metrics.setHeapMaxMB(heap.getMax() / 1024 / 1024);
        metrics.setHeapCommittedMB(heap.getCommitted() / 1024 / 1024);
        metrics.setHeapUsagePercent(percent(heap.getUsed(), heap.getMax()));

        // Non-Heap
        MemoryUsage nonHeap = memoryMXBean.getNonHeapMemoryUsage();
        metrics.setNonHeapUsedMB(nonHeap.getUsed() / 1024 / 1024);
        metrics.setNonHeapMaxMB(nonHeap.getMax() / 1024 / 1024);
        metrics.setNonHeapUsagePercent(percent(nonHeap.getUsed(), nonHeap.getMax()));

        // Metaspace（完整统计）
        collectMetaspace(metrics);
    }

    private static void collectMetaspace(JVMMetrics metrics) {
        try {
            List<MemoryPoolMXBean> beans = ManagementFactory.getMemoryPoolMXBeans();

            long metaspaceUsed = 0L;
            long metaspaceCommitted = 0L;
            long metaspaceMax = 0L;
            long compressedClassUsed = 0L;

            for (MemoryPoolMXBean bean : beans) {
                String name = bean.getName().toLowerCase();
                MemoryUsage usage = bean.getUsage();
                if (usage == null) continue;

                // Metaspace 和 Compressed Class Space 分开统计，
                // 与 JConsole 对齐：JConsole 的 Metaspace 不含 Compressed Class Space
                if (name.contains("metaspace") && !name.contains("compressed class")) {
                    metaspaceUsed += usage.getUsed();
                    metaspaceCommitted += usage.getCommitted();
                    long max = usage.getMax();
                    // 未设置 -XX:MaxMetaspaceSize 时 getMax() 返回 -1，此时用 committed 兜底，
                    // 与 JConsole 在 max 缺省时按 committed 绘制上限的行为一致
                    if (max > 0) {
                        metaspaceMax += max;
                    }
                } else if (name.contains("compressed class")) {
                    compressedClassUsed = usage.getUsed();
                }
            }

            metrics.setMetaspaceUsedKB(metaspaceUsed / 1024);
            metrics.setMetaspaceCommittedKB(metaspaceCommitted / 1024);
            // max 缺省时退化为 committed，避免前端展示 -1
            long effectiveMax = metaspaceMax > 0 ? metaspaceMax : metaspaceCommitted;
            metrics.setMetaspaceMaxKB(effectiveMax / 1024);
            metrics.setCompressedClassSpaceUsedKB(compressedClassUsed / 1024);

            double percent;
            if (effectiveMax > 0) {
                percent = metaspaceUsed * 100.0 / effectiveMax;
            } else {
                percent = 0.0;
            }

            metrics.setMetaspaceUsagePercent(
                    Math.round(percent * 100.0) / 100.0
            );

        } catch (Exception e) {
            log.debug("Failed to collect metaspace metrics", e);

            metrics.setMetaspaceUsedKB(0);
            metrics.setMetaspaceCommittedKB(0);
            metrics.setMetaspaceMaxKB(0);
            metrics.setMetaspaceUsagePercent(0.0);
            metrics.setCompressedClassSpaceUsedKB(0);
        }
    }

    // ================== Class ==================

    private static void collectClassMetrics(JVMMetrics metrics) {
        ClassLoadingMXBean bean = ManagementFactory.getClassLoadingMXBean();

        metrics.setLoadedClassCount(bean.getLoadedClassCount());
        metrics.setTotalLoadedClassCount(bean.getTotalLoadedClassCount());
        metrics.setUnloadedClassCount(bean.getUnloadedClassCount());
    }

    // ================== Thread ==================

    private static void collectThreadMetrics(JVMMetrics metrics) {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();

        metrics.setThreadCount(bean.getThreadCount());
        metrics.setDaemonThreadCount(bean.getDaemonThreadCount());
        metrics.setPeakThreadCount(bean.getPeakThreadCount());
    }

    // ================== GC ==================

    private static void collectGCMetrics(JVMMetrics metrics) {
        long count = 0;
        long time = 0;
        List<GcDetail> details = new java.util.ArrayList<>();

        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long gcCount = gc.getCollectionCount() >= 0 ? gc.getCollectionCount() : 0;
            long gcTime = gc.getCollectionTime() >= 0 ? gc.getCollectionTime() : 0;

            count += gcCount;
            time += gcTime;

            details.add(new GcDetail(gc.getName(), gcCount, gcTime));
        }

        metrics.setGcCount(count);
        metrics.setGcTimeMs(time);
        metrics.setGcDetails(details);
    }

    // ================== CPU ==================

    private static void collectCPUMetrics(JVMMetrics metrics) {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

        metrics.setAvailableProcessors(osBean.getAvailableProcessors());

        double load = osBean.getSystemLoadAverage();
        metrics.setSystemLoadAverage(load >= 0 ? load : 0.0);

        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            try {
                com.sun.management.OperatingSystemMXBean sun =
                        (com.sun.management.OperatingSystemMXBean) osBean;

                double cpu = sun.getProcessCpuLoad();

                if (cpu >= 0) {
                    metrics.setProcessCpuLoad(cpu);
                    // 保留一位小数，与 JConsole 精度对齐
                    metrics.setCpuUsage(Math.round(cpu * 1000) / 10.0);
                } else {
                    metrics.setProcessCpuLoad(0.0);
                    metrics.setCpuUsage(0.0);
                }

            } catch (Exception e) {
                log.debug("Failed to collect CPU metrics", e);
                metrics.setProcessCpuLoad(0.0);
                metrics.setCpuUsage(0.0);
            }
        }
    }
}
