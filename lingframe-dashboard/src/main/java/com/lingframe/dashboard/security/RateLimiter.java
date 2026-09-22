package com.lingframe.dashboard.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API 限流决策器：基于 IP + 路径的令牌桶限流。
 * <p>
 * 主干逻辑，与 Servlet 命名空间无关。{@code RateLimitFilter} 薄壳委托本类做限流判断，
 * 并通过其自身的 {@code @Scheduled} 定时调用 {@link #cleanupIdleBuckets()} 清理空闲桶
 * （本类非 Spring Bean，不自行承载调度）。
 *
 * @author lingframe
 */
@Slf4j
@RequiredArgsConstructor
public class RateLimiter {

    private final RateLimitProperties properties;
    private final Map<String, TokenBucket> perIpBuckets = new ConcurrentHashMap<>();

    /**
     * 判断请求是否放行。
     *
     * @param snapshot 请求快照
     * @return 放行决策或 429 终止决策
     */
    public SecurityDecision check(RequestSnapshot snapshot) {
        String path = snapshot.getRequestUri();
        if (!path.startsWith("/lingframe/dashboard/") || path.startsWith("/lingframe/dashboard/ui")) {
            return SecurityDecision.proceed();
        }

        String clientIp = getClientIp(snapshot);
        TokenBucket bucket = perIpBuckets.computeIfAbsent(clientIp,
                k -> new TokenBucket(properties.getMaxRequestsPerSecond()));

        if (!bucket.tryAcquire()) {
            log.warn("Request rate limited: ip={}, path={}", clientIp, path);
            return SecurityDecision.terminate(429, "application/json;charset=UTF-8",
                    "{\"success\":false,\"message\":\"请求过于频繁，请稍后重试\"}");
        }
        return SecurityDecision.proceed();
    }

    /**
     * 清理空闲令牌桶。由分支 {@code RateLimitFilter} 的 {@code @Scheduled} 定时调用。
     */
    public void cleanupIdleBuckets() {
        long now = System.currentTimeMillis();
        perIpBuckets.entrySet().removeIf(e ->
                now - e.getValue().lastAccessTime > properties.getIpIdleThresholdMs());
    }

    private String getClientIp(RequestSnapshot snapshot) {
        String remote = snapshot.getRemoteAddr();
        if (properties.isTrustedProxy(remote)) {
            String xff = snapshot.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isEmpty()) {
                return xff.split(",")[0].trim();
            }
        }
        return remote;
    }

    private static class TokenBucket {
        private final int capacity;
        private long tokens;
        private long lastRefillTime = System.currentTimeMillis();
        volatile long lastAccessTime = System.currentTimeMillis();

        TokenBucket(int capacity) {
            this.capacity = capacity;
            this.tokens = capacity;
        }

        synchronized boolean tryAcquire() {
            refillLocked();
            lastAccessTime = System.currentTimeMillis();
            if (tokens <= 0) {
                return false;
            }
            tokens--;
            return true;
        }

        private void refillLocked() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTime;
            if (elapsed >= 1000) {
                long newTokens = (elapsed / 1000) * capacity;
                tokens = Math.min(capacity, tokens + newTokens);
                lastRefillTime = now;
            }
        }
    }
}
