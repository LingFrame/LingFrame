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
 * 简易 API 限流 Filter：基于 IP + 路径的令牌桶限流
 * 自行实现令牌桶算法，不依赖 Guava RateLimiter
 *
 * <p>安全策略：默认不信任 X-Forwarded-For / X-Real-IP 头，
 * 仅当直连来自 {@link RateLimitProperties#getTrustedProxyIps()} 中的受信代理时才解析。
 * 这避免攻击者伪造代理头绕过限流。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class RateLimitFilter implements Filter {

    private final RateLimitProperties properties;

    /** IP 维度的令牌桶 */
    private final Map<String, TokenBucket> perIpBuckets = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties properties) {
        this.properties = properties;
    }

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
        TokenBucket bucket = perIpBuckets.computeIfAbsent(clientIp, k -> new TokenBucket(properties.getMaxRequestsPerSecond()));

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

    /**
     * 提取客户端真实 IP。
     *
     * <p>第一性原理：反向代理头（X-Forwarded-For / X-Real-IP）不可信，除非显式配置受信代理。
     * 默认用 TCP 直连 IP（{@code request.getRemoteAddr()}），
     * 仅当直连来自受信代理 IP 时才解析 X-Forwarded-For 取原始客户端 IP。
     */
    private String getClientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        // 仅当直连来自受信代理时，才解析 X-Forwarded-For
        if (properties.isTrustedProxy(remote)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isEmpty()) {
                // X-Forwarded-For 可能包含多个 IP，取第一个（最原始的客户端 IP）
                return xff.split(",")[0].trim();
            }
        }
        return remote;
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
        private final int capacity;
        // refill 与 acquire 同在 synchronized 块内读写，无需 AtomicLong，普通 long 即可
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
                now - e.getValue().lastAccessTime > properties.getIpIdleThresholdMs());
    }
}
