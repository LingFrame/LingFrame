package com.lingframe.dashboard.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 灵元隔离线程池状态。
 */
@Data
@Builder
public class ThreadPoolStatsDTO {
    private String lingId;
    private int activeCount;
    private int poolSize;
    private int maxThreads;
    private int queueSize;
    private long completedTaskCount;
}
