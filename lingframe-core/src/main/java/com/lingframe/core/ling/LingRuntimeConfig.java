package com.lingframe.core.ling;

import lombok.Builder;
import lombok.Getter;

/**
 * 灵元运行时配置
 */
@Getter
@Builder(toBuilder = true)
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

    /**
     * 卸载前飞行中请求排空（drain）的 await 切片粒度（毫秒）。
     * <p>
     * drain 已改为事件驱动等待（{@link LingInstance#awaitIdle}），
     * exit() 引用计数归零时主动 signal 唤醒 drain 线程，无需周期性轮询。
     * 此配置作为 awaitIdle 的单次等待超时粒度，兼作 deadline 兜底检查间隔：
     * <ul>
     *   <li>生产环境可调大到 100~200ms，减少 deadline 检查开销；</li>
     *   <li>测试或低延迟场景可调小到 10ms，加快 deadline 截止判定。</li>
     * </ul>
     * 实际请求结束的唤醒由 exit() 的 signal 触发，与此值无关。
     */
    @Builder.Default
    private int drainPollIntervalMs = 50;

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

    /**
     * 限流 QPS（每秒令牌数），0 表示不启用
     */
    @Builder.Default
    private int rateLimitPerSecond = 0;

    // ==================== 熔断器 ====================

    /**
     * 熔断失败率阈值（百分比，0-100）。
     * 滑动窗口内失败率达到此阈值时触发熔断。
     */
    @Builder.Default
    private int circuitBreakerFailureRateThreshold = 50;

    /**
     * 熔断慢调用率阈值（百分比，0-100）。
     * 滑动窗口内慢调用（超过 defaultTimeoutMs）率达到此阈值时触发熔断。
     */
    @Builder.Default
    private int circuitBreakerSlowCallRateThreshold = 80;

    /**
     * 熔断滑动窗口大小（调用次数）。
     */
    @Builder.Default
    private int circuitBreakerSlidingWindowSize = 20;

    /**
     * 熔断最小调用数。
     * 滑动窗口内调用数未达到此值时不触发熔断判定，避免冷启动误熔断。
     */
    @Builder.Default
    private int circuitBreakerMinimumNumberOfCalls = 10;

    /**
     * 熔断器开启后等待时间（毫秒），0 表示用 defaultTimeoutMs * 10。
     */
    @Builder.Default
    private long circuitBreakerWaitDurationInOpenStateMs = 0;

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
                .defaultTimeoutMs(30000) // 30秒，方便调试
                .bulkheadMaxConcurrent(100)
                .forceCleanupDelaySeconds(5) // 快速清理
                .build();
    }

    @Override
    public String toString() {
        return String.format(
                "LingRuntimeConfig{maxHistory=%d, timeout=%dms, bulkhead=%d, rateLimit=%d/s}",
                maxHistorySnapshots, defaultTimeoutMs, bulkheadMaxConcurrent, rateLimitPerSecond);
    }
}
