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
import java.util.concurrent.atomic.AtomicLong;

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
            log.warn("请求限流: ip={}, path={}", clientIp, path);
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
     */
    private static class TokenBucket {
        private final int capacity = MAX_REQUESTS_PER_SECOND;
        private final AtomicLong tokens = new AtomicLong(capacity);
        private volatile long lastRefillTime = System.currentTimeMillis();
        volatile long lastAccessTime = System.currentTimeMillis();

        boolean tryAcquire() {
            refill();
            lastAccessTime = System.currentTimeMillis();
            while (true) {
                long current = tokens.get();
                if (current <= 0) {
                    return false;
                }
                if (tokens.compareAndSet(current, current - 1)) {
                    return true;
                }
            }
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTime;
            if (elapsed >= 1000) {
                long newTokens = (elapsed / 1000) * capacity;
                long current = tokens.get();
                long newCount = Math.min(capacity, current + newTokens);
                tokens.set(newCount);
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
