package com.lingframe.dashboard.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简易 API 限流 Filter：基于 IP + 路径的滑动窗口限流
 * 使用 Guava RateLimiter 不额外引入依赖，用令牌桶算法自行实现
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class RateLimitFilter implements Filter {

    private static final int MAX_REQUESTS_PER_SECOND = 30;

    /** IP 不活跃超过此时间（毫秒）后清理 */
    private static final long IP_IDLE_THRESHOLD_MS = 600_000; // 10 分钟

    // IP 维度的令牌桶
    private final Map<String, TokenBucket> perIpBuckets = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();

        // 仅限流 API 端点，静态资源不限
        if (!path.startsWith("/lingframe/dashboard/") || path.startsWith("/lingframe/dashboard/ui")) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = getClientIp(httpRequest);
        TokenBucket bucket = perIpBuckets.computeIfAbsent(clientIp, k -> new TokenBucket());

        if (!bucket.tryAcquire()) {
            log.warn("Request rate limited: ip={}, path={}", clientIp, path);
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus(429);
            httpResponse.setContentType("application/json;charset=UTF-8");
            httpResponse.getWriter().write("{\"success\":false,\"message\":\"请求过于频繁，请稍后重试\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty()) {
            ip = request.getRemoteAddr();
        }
        // X-Forwarded-For 可能包含多个 IP，取第一个
        if (ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    /**
     * 简易令牌桶：每秒补充令牌，桶容量有限
     * <p>
     * 并发模型：refill + 消费 合并为单个 synchronized 原子方法，
     * 消除「读-补-写」窗口。此前 refill 已加锁但 tryAcquire 在锁外读取 tokens，
     * 多线程并发时会出现：A 读到 5 → B 读到 5 → A 补到 8 写入 → B 补到 8 写入
     * （B 应基于 A 写入后的值补，导致令牌超发）。改为整段加锁后语义清晰且无超发。
     */
    private static class TokenBucket {
        private final int capacity = MAX_REQUESTS_PER_SECOND;
        // refill 与 acquire 同在 synchronized 块内读写，无需 AtomicLong，普通 long 即可
        private long tokens = capacity;
        private long lastRefillTime = System.currentTimeMillis();
        volatile long lastAccessTime = System.currentTimeMillis();

        synchronized boolean tryAcquire() {
            refillLocked();
            lastAccessTime = System.currentTimeMillis();
            if (tokens <= 0) {
                return false;
            }
            tokens--;
            return true;
        }

        /**
         * 在已持有 bucket 锁的前提下补充令牌。仅由 {@link #tryAcquire()} 调用。
         */
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

    /**
     * 定时清理不活跃 IP 的令牌桶，防止内存泄漏
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void cleanupIdleBuckets() {
        long now = System.currentTimeMillis();
        perIpBuckets.entrySet().removeIf(e ->
                now - e.getValue().lastAccessTime > IP_IDLE_THRESHOLD_MS);
    }
}
