package com.lingframe.core.resilience;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 令牌桶限流器 (Token Bucket)
 * <p>
 * 支持突发流量允许，平滑请求。
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private final String name;
    private final double ratePerSecond; // 每秒生成令牌数
    private final double maxTokens; // 桶容量

    // 状态
    private final AtomicReference<State> state;

    private record State(double tokens, long lastRefillTime) {
    }

    public TokenBucketRateLimiter(String name, double ratePerSecond, double maxTokens) {
        this.name = name;
        this.ratePerSecond = ratePerSecond;
        this.maxTokens = maxTokens;

        // 初始装满
        this.state = new AtomicReference<>(new State(maxTokens, System.nanoTime()));
    }

    @Override
    public boolean tryAcquire() {
        while (true) {
            State current = state.get();
            long now = System.nanoTime();

            // 计算新增令牌
            long durationNs = now - current.lastRefillTime;
            double filledTokens = (durationNs * ratePerSecond) / 1_000_000_000.0;

            double newTokens = Math.min(maxTokens, current.tokens + filledTokens);

            if (newTokens >= 1.0) {
                // 有足够的令牌，扣减 1
                State next = new State(newTokens - 1.0, now);
                if (state.compareAndSet(current, next)) {
                    return true;
                }
                // CAS 失败，重试
            } else {
                // 令牌不足
                // 也要更新时间，防止下次计算时 duration 过大导致精度问题，或者仅仅是为了 lazy update？
                // 如果不更新，下次进来还是基于很久以前的时间算。
                // 实际上如果不更新，下次别人进来算出很多 token，可能就能过了。
                // 简单的做法是：如果不扣减，就不更新状态，直接返回 false。
                // 但为了避免 recalculation from very old timestamp，可以不更新。
                return false;
            }
        }
    }

    @Override
    public String getName() {
        return name;
    }
}
