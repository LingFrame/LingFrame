package com.lingframe.dashboard.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 灵元资源占用指标。
 * <p>
 * 类数、线程数、CPU 时间为精确值；Metaspace 为基于类数估算的近似值。
 */
@Data
@Builder
public class LingResourceMetricsDTO {
    private String lingId;
    private String version;
    /** 已加载类数（精确） */
    private int loadedClassCount;
    /** 活跃线程数（精确，按 TCCL 分组） */
    private int activeThreadCount;
    /** 累计 CPU 时间（毫秒，精确） */
    private long cpuTimeMs;
    /** 采样周期内的线程分配字节增量（精确） */
    private long estimatedHeapDeltaBytes;
    /** Metaspace 估算占用（字节，近似） */
    private long estimatedMetaspaceBytes;
    private long timestamp;
}
