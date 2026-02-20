package com.lingframe.core.ling;

import lombok.Builder;
import lombok.Getter;

/**
 * 单元运行时配置
 */
@Getter
@Builder
public class LingRuntimeConfig {

    // ==================== 实例管理 ====================

    /**
     * 最大历史快照数量（OOM 防御）
     * 超过此数量时拒绝新的部署
     */
    @Builder.Default
    private int maxHistorySnapshots = 5;

    /**
     * 强制清理延迟时间（秒）
     * 卸载后等待多久强制销毁未归零的实例
     */
    @Builder.Default
    private int forceCleanupDelaySeconds = 30;

    /**
     * 死亡队列检查间隔（秒）
     */
    @Builder.Default
    private int dyingCheckIntervalSeconds = 5;

    // ==================== 调用控制 ====================

    /**
     * 默认超时时间（毫秒）
     */
    @Builder.Default
    private int defaultTimeoutMs = 3000;

    /**
     * 舱壁隔离：最大并发请求数
     */
    @Builder.Default
    private int bulkheadMaxConcurrent = 10;

    /**
     * 舱壁获取许可超时（毫秒）
     * 设为与 defaultTimeoutMs 相同
     */
    @Builder.Default
    private int bulkheadAcquireTimeoutMs = 3000;

    // ==================== 工厂方法 ====================

    /**
     * 默认配置
     */
    public static LingRuntimeConfig defaults() {
        return LingRuntimeConfig.builder().build();
    }

    /**
     * 高并发场景配置
     */
    public static LingRuntimeConfig highConcurrency() {
        return LingRuntimeConfig.builder()
                .bulkheadMaxConcurrent(50)
                .defaultTimeoutMs(5000)
                .bulkheadAcquireTimeoutMs(5000)
                .build();
    }

    /**
     * 低延迟场景配置
     */
    public static LingRuntimeConfig lowLatency() {
        return LingRuntimeConfig.builder()
                .defaultTimeoutMs(1000)
                .bulkheadAcquireTimeoutMs(500)
                .bulkheadMaxConcurrent(20)
                .build();
    }

    /**
     * 开发模式配置（更宽松）
     */
    public static LingRuntimeConfig development() {
        return LingRuntimeConfig.builder()
                .maxHistorySnapshots(10)
                .defaultTimeoutMs(30000)  // 30秒，方便调试
                .bulkheadMaxConcurrent(100)
                .forceCleanupDelaySeconds(5)  // 快速清理
                .build();
    }

    @Override
    public String toString() {
        return String.format(
                "LingRuntimeConfig{maxHistory=%d, timeout=%dms, bulkhead=%d}",
                maxHistorySnapshots, defaultTimeoutMs, bulkheadMaxConcurrent
        );
    }
}