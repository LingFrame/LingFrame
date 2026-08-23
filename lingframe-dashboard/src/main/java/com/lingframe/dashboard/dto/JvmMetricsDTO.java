package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.lingframe.core.metrics.GcDetail;

import java.util.List;

/**
 * JVM 综合指标 DTO，替代 Controller 层手拼 HashMap。
 * 字段分组：CPU / 内存 / 堆 / Metaspace / 类加载 / 线程 / GC / 系统信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JvmMetricsDTO {

    // ==================== CPU ====================
    /** 系统 CPU 使用率（0.0 ~ 1.0） */
    private double cpuUsage;
    /** 进程 CPU 负载（0.0 ~ 1.0） */
    private double processCpuLoad;

    // ==================== 内存 ====================
    /** 已用堆外内存 (MB) */
    private long memoryUsedMB;
    /** 总物理内存 (MB) */
    private long memoryTotalMB;
    /** 内存使用率（0.0 ~ 1.0） */
    private double memoryUsage;

    // ==================== 堆 ====================
    /** 已用堆内存 (MB) */
    private long heapUsedMB;
    /** 堆最大内存 (MB) */
    private long heapMaxMB;
    /** 堆使用率（0.0 ~ 1.0） */
    private double heapUsage;

    // ==================== Metaspace ====================
    /** Metaspace 已用 (KB) */
    private long metaspaceUsedKB;
    /** Metaspace 最大 (KB) */
    private long metaspaceMaxKB;
    /** Metaspace 使用率（0.0 ~ 1.0） */
    private double metaspaceUsage;
    /** 压缩类空间已用 (KB)——类定义(Klass)元数据区，类加载器卸载后回落，是"元空间回收"的证明指标 */
    private long compressedClassSpaceUsedKB;

    // ==================== 类加载 ====================
    /** 当前已加载类数量 */
    private long loadedClassCount;
    /** 历史累计加载类数量 */
    private long totalLoadedClassCount;
    /** 已卸载类数量 */
    private long unloadedClassCount;
    /** 当前存活的灵元 ClassLoader 数量（内存泄漏监控关键指标） */
    private long lingClassLoaderCount;

    // ==================== 线程 ====================
    /** 当前活跃线程数 */
    private int threadCount;
    /** 守护线程数 */
    private int daemonThreadCount;
    /** 峰值线程数 */
    private int peakThreadCount;

    // ==================== GC ====================
    /** 全局 GC 次数（所有收集器汇总） */
    private long gcCount;
    /** 全局 GC 耗时 ms（所有收集器汇总） */
    private long gcTimeMs;
    /** 按收集器分离的 GC 详情（name / count / timeMs） */
    private List<GcDetail> gcDetails;

    // ==================== 系统信息 ====================
    /** 可用处理器数量 */
    private int availableProcessors;
    /** 系统平均负载（1 分钟，-1 表示不支持） */
    private double systemLoadAverage;
    /** JVM 版本 */
    private String jvmVersion;
    /** JVM 厂商 */
    private String jvmVendor;
    /** 操作系统名称 */
    private String osName;
    /** 操作系统架构 */
    private String osArch;
    /** JVM 运行时长 (ms) */
    private long uptimeMs;
    /** 进程 ID */
    private String pid;
}
