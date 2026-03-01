package com.lingframe.core.pipeline;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.kernel.LingInvocationException;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRuntimeConfig;
import com.lingframe.core.resilience.CircuitBreaker;
import com.lingframe.core.resilience.RateLimiter;
import com.lingframe.core.resilience.SlidingWindowCircuitBreaker;
import com.lingframe.core.resilience.TokenBucketRateLimiter;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 弹性治理 Filter。
 * <p>
 * 职责：
 * 1. 限流（RateLimiter）—— 拒绝超过速率限制的请求
 * 2. 熔断（CircuitBreaker）—— 根据失败率自动隔离故障单元
 * <p>
 * 弹性组件按 lingId 懒创建，参数来自 {@link LingRuntimeConfig}。
 */
@Slf4j
public class ResilienceGovernanceFilter implements LingInvocationFilter {

    private final LingRepository lingRepository;
    private final EventBus eventBus;

    // 按 lingId 管理弹性组件实例
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    public ResilienceGovernanceFilter(LingRepository lingRepository, EventBus eventBus) {
        this.lingRepository = lingRepository;
        this.eventBus = eventBus;
    }

    /** 无参构造保持向后兼容（弹性治理不生效，仅透传） */
    public ResilienceGovernanceFilter() {
        this.lingRepository = null;
        this.eventBus = null;
    }

    @Override
    public int getOrder() {
        return FilterPhase.RESILIENCE;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        String fqsid = ctx.getServiceFQSID();
        if (fqsid == null || lingRepository == null) {
            return chain.doFilter(ctx);
        }

        String lingId = fqsid.split(":")[0];

        // 1. 限流检查
        RateLimiter limiter = getLimiter(lingId);
        if (limiter != null && !limiter.tryAcquire()) {
            log.warn("[Resilience:{}] Rate limited, rejecting request: {}", lingId, fqsid);
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.RATE_LIMITED);
        }

        // 2. 熔断检查
        CircuitBreaker breaker = getBreaker(lingId);
        if (breaker != null && !breaker.tryAcquirePermission()) {
            log.warn("[Resilience:{}] Circuit breaker OPEN, rejecting request: {}", lingId, fqsid);
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.CIRCUIT_OPEN);
        }

        // 3. 执行并记录结果
        long startNanos = System.nanoTime();
        try {
            Object result = chain.doFilter(ctx);
            if (breaker != null) {
                long durationNanos = System.nanoTime() - startNanos;
                breaker.onSuccess(durationNanos, TimeUnit.NANOSECONDS);
            }
            return result;
        } catch (Throwable t) {
            if (breaker != null) {
                long durationNanos = System.nanoTime() - startNanos;
                breaker.onError(durationNanos, TimeUnit.NANOSECONDS, t);
            }
            throw t;
        }
    }

    private CircuitBreaker getBreaker(String lingId) {
        return breakers.computeIfAbsent(lingId, id -> {
            LingRuntime runtime = lingRepository.getRuntime(id);
            if (runtime == null)
                return null;
            LingRuntimeConfig config = runtime.getConfig();
            return new SlidingWindowCircuitBreaker(
                    id,
                    50, // 失败率阈值 50%
                    80, // 慢调用率阈值 80%
                    config.getDefaultTimeoutMs(), // 慢调用判定阈值
                    20, // 滑动窗口大小
                    10, // 最小调用数
                    config.getDefaultTimeoutMs() * 10L, // 熔断后等待时间
                    eventBus);
        });
    }

    private RateLimiter getLimiter(String lingId) {
        return limiters.computeIfAbsent(lingId, id -> {
            LingRuntime runtime = lingRepository.getRuntime(id);
            if (runtime == null)
                return null;
            LingRuntimeConfig config = runtime.getConfig();
            // 使用 bulkheadMaxConcurrent 作为限流 QPS 基线
            return new TokenBucketRateLimiter(id, config.getBulkheadMaxConcurrent(), config.getBulkheadMaxConcurrent());
        });
    }

    /**
     * 单元卸载时驱逐弹性实例，防止内存泄漏
     */
    public void evict(String lingId) {
        breakers.remove(lingId);
        limiters.remove(lingId);
    }
}
