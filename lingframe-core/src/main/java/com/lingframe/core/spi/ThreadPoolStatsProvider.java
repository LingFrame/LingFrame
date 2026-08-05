package com.lingframe.core.spi;

import java.util.Collections;
import java.util.List;

/**
 * 线程池状态查询 SPI。
 * <p>
 * 由持有隔离线程池的组件实现，供 dashboard 查询各灵元线程池运行状态。
 */
public interface ThreadPoolStatsProvider {

    /**
     * 线程池状态行
     */
    final class ThreadPoolStats {
        private final String lingId;
        private final int activeCount;
        private final int poolSize;
        private final int maxThreads;
        private final int queueSize;
        private final long completedTaskCount;

        public ThreadPoolStats(String lingId, int activeCount, int poolSize,
                               int maxThreads, int queueSize, long completedTaskCount) {
            this.lingId = lingId;
            this.activeCount = activeCount;
            this.poolSize = poolSize;
            this.maxThreads = maxThreads;
            this.queueSize = queueSize;
            this.completedTaskCount = completedTaskCount;
        }

        public String getLingId() { return lingId; }
        public int getActiveCount() { return activeCount; }
        public int getPoolSize() { return poolSize; }
        public int getMaxThreads() { return maxThreads; }
        public int getQueueSize() { return queueSize; }
        public long getCompletedTaskCount() { return completedTaskCount; }
    }

    /**
     * 获取所有灵元的线程池状态
     */
    List<ThreadPoolStats> getThreadPoolStats();

    /**
     * 空实现兜底
     */
    ThreadPoolStatsProvider NO_OP = () -> Collections.emptyList();
}
