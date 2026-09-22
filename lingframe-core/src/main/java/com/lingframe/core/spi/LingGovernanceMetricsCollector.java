package com.lingframe.core.spi;

/**
 * 治理指标采集器内核抽象。
 * <p>
 * 微内核解耦：内核（ling 包）只依赖此接口，具体实现由 metrics 扩展包提供。
 * 默认方法为空实现，避免装配未提供实现时强制依赖。
 */
public interface LingGovernanceMetricsCollector {

    /**
     * 记录 drain 超时后的强制推进次数（force-drain）。
     */
    default void recordForceDrain(String lingId, String version) {
        // optional
    }

    /**
     * 记录 drain 超时且因 wait-only 策略拒绝卸载的次数。
     */
    default void recordDrainTimeoutAbort(String lingId, String version) {
        // optional
    }
}
