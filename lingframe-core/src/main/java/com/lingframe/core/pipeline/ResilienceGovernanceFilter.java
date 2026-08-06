package com.lingframe.core.pipeline;

import com.lingframe.api.resilience.FallbackCause;
import com.lingframe.api.resilience.FallbackProvider;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRuntimeConfig;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
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
 * 2. 熔断（CircuitBreaker）—— 根据失败率自动隔离故障灵元
 * 3. DEGRADED 自愈 —— 熔断打开时将灵元降级，恢复后自动回到 ACTIVE
 * <p>
 * 弹性组件按 lingId 懒创建，参数来自 {@link LingRuntimeConfig}。
 */
@Slf4j
public class ResilienceGovernanceFilter implements LingInvocationFilter {

    private final LingRepository lingRepository;
    private final EventBus eventBus;
    private final RuntimeCoordinator runtimeCoordinator;
    private final GovernanceMetricsCollector governanceMetricsCollector;

    /** 可插拔的降级策略，为 null 时直接抛异常 */
    private volatile FallbackProvider fallbackProvider;

    // 按 lingId 管理弹性组件实例
    // breakers 存 BreakerHolder 而非 CircuitBreaker，是为了在 timeoutMs 变化时能检测并重建实例
    private final ConcurrentHashMap<String, BreakerHolder> breakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LimiterHolder> limiters = new ConcurrentHashMap<>();

    public ResilienceGovernanceFilter(LingRepository lingRepository, EventBus eventBus, RuntimeCoordinator runtimeCoordinator) {
        this(lingRepository, eventBus, runtimeCoordinator, null);
    }

    public ResilienceGovernanceFilter(LingRepository lingRepository, EventBus eventBus, RuntimeCoordinator runtimeCoordinator,
                                      GovernanceMetricsCollector governanceMetricsCollector) {
        this.lingRepository = lingRepository;
        this.eventBus = eventBus;
        this.runtimeCoordinator = runtimeCoordinator;
        this.governanceMetricsCollector = governanceMetricsCollector;
    }

    public ResilienceGovernanceFilter(LingRepository lingRepository, EventBus eventBus) {
        this(lingRepository, eventBus, null, null);
    }

    /** 无参构造保持向后兼容（弹性治理不生效，仅透传） */
    public ResilienceGovernanceFilter() {
        this(null, null, null, null);
    }

    /**
     * 设置降级策略。
     * <p>
     * 设置后，熔断打开或限流拒绝时将优先调用降级策略获取响应，
     * 仅当降级策略返回 null 时才抛出异常。
     */
    public void setFallbackProvider(FallbackProvider fallbackProvider) {
        this.fallbackProvider = fallbackProvider;
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

        // SIMULATION 干跑（Dashboard 压测/模拟）：不消费真实限流预算、不污染熔断器统计。
        // 模拟流量是验证路由与治理链的探针，不是真实业务流量——高频压测若被限流打回，
        // 则永远压测不出分流效果；模拟失败若记入熔断器，会把真实灵元误判为故障。
        if (ctx.execution().getMode().isSimulation()) {
            return chain.doFilter(ctx);
        }

        String lingId = ctx.getEffectiveLingId();

        // 1. 限流检查
        RateLimiter limiter = getLimiter(lingId, ctx);
        if (limiter != null && !limiter.tryAcquire()) {
            // WARN 而非 DEBUG：限流拒绝是业务可见的异常路径，必须可观测（限流值/目标版本/调用方），
            // 否则生产报表里「频繁 RATE_LIMITED」时无日志可查，只能靠猜。
            LingRuntime runtimeForLog = lingRepository.getRuntime(lingId);
            int effectiveRateLimit = runtimeForLog != null ? resolveRateLimit(ctx, runtimeForLog.getConfig()) : -1;
            log.warn("[Resilience:{}] Rate limited, rejecting request: {}, rateLimit={}/s, targetVersion={}",
                    lingId, fqsid, effectiveRateLimit, ctx.getTargetVersion());
            if (governanceMetricsCollector != null) {
                governanceMetricsCollector.recordRateLimited(lingId, ctx.getTargetVersion());
            }
            Object fallbackResult = tryFallback(fqsid, FallbackCause.RATE_LIMITED);
            if (fallbackResult != null) {
                return fallbackResult;
            }
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.RATE_LIMITED);
        }

        // 2. 熔断检查
        CircuitBreaker breaker = getBreaker(lingId, ctx);
        if (breaker != null && !breaker.tryAcquirePermission()) {
            log.warn("[Resilience:{}] Circuit breaker OPEN, rejecting request: {}, targetVersion={}",
                    lingId, fqsid, ctx.getTargetVersion());
            if (governanceMetricsCollector != null) {
                governanceMetricsCollector.recordCircuitOpenRejected(lingId, ctx.getTargetVersion());
            }

            // 熔断打开 → 将灵元宏观状态转为 DEGRADED
            transitionToDegraded(lingId, ctx.getTargetVersion());

            Object fallbackResult = tryFallback(fqsid, FallbackCause.CIRCUIT_OPEN);
            if (fallbackResult != null) {
                return fallbackResult;
            }
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.CIRCUIT_OPEN);
        }

        // 3. 执行并记录结果
        long startNanos = System.nanoTime();
        try {
            Object result = chain.doFilter(ctx);
            if (breaker != null) {
                long durationNanos = System.nanoTime() - startNanos;
                breaker.onSuccess(durationNanos, TimeUnit.NANOSECONDS);

                // 🔥 执行成功后检查熔断器是否已恢复，如果恢复则转回 ACTIVE
                tryRecoverFromDegraded(lingId, breaker);
            }
            return result;
        } catch (Error e) {
            // Error（OOM / StackOverflow）跳过熔断器 onError 副作用直接透传，
            // 避免在 JVM 即将崩溃时再触发 breaker.onError 导致二次错误。
            throw e;
        } catch (Throwable t) {
            if (breaker != null) {
                long durationNanos = System.nanoTime() - startNanos;
                breaker.onError(durationNanos, TimeUnit.NANOSECONDS, t);
            }
            throw t;
        }
    }

    /**
     * 获取或构建灵元的熔断器。
     * <p>
     * 慢调用阈值（timeoutMs）优先读 ctx.governance()（由 InvocationPolicyPrefillFilter
     * 在 RESILIENCE 之前预填充），回退 LingRuntimeConfig 静态默认值。
     * 当 timeoutMs 变化时（治理下发后预填充值改变），用 compute 原子重建熔断器实例，
     * 与 {@link #getLimiter} 的 LimiterHolder 模式对称。
     * <p>
     * B 类熔断参数（failureRate/slowCallRate/slidingWindowSize/minimumCalls/waitDuration）
     * 仍读 config 静态默认值，这些参数没有"双写"问题，不进入治理下发链路。
     */
    private CircuitBreaker getBreaker(String lingId, InvocationContext ctx) {
        // 快速路径：缓存命中且未治理下发 timeout 时直接返回，避免每次请求都读 runtime/config。
        // 与 {@link #getLimiter} 的快速路径对称：governedTimeout 为 null 表示无治理下发，
        // 此时 effectiveTimeout 会回退到 config 静态默认值，与 holder 创建时的 timeoutMs 一致，缓存有效。
        BreakerHolder holder = breakers.get(lingId);
        if (holder != null) {
            Integer governedTimeout = ctx.governance().getTimeoutMs();
            if (governedTimeout == null) {
                return holder.breaker;
            }
        }

        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            return null;
        }
        LingRuntimeConfig config = runtime.getConfig();
        // 读 ctx.governance()（预填充后有值），回退 config 静态默认值
        Integer governedTimeout = ctx.governance().getTimeoutMs();
        int effectiveTimeout = governedTimeout != null ? governedTimeout : config.getDefaultTimeoutMs();

        if (holder != null && holder.timeoutMs == effectiveTimeout) {
            return holder.breaker;
        }
        holder = breakers.compute(lingId, (id, existing) -> {
            if (existing != null && existing.timeoutMs == effectiveTimeout) {
                return existing;
            }
            long waitDuration = config.getCircuitBreakerWaitDurationInOpenStateMs() > 0
                    ? config.getCircuitBreakerWaitDurationInOpenStateMs()
                    : effectiveTimeout * 10L;
            CircuitBreaker breaker = new SlidingWindowCircuitBreaker(
                    id,
                    config.getCircuitBreakerFailureRateThreshold(),
                    config.getCircuitBreakerSlowCallRateThreshold(),
                    effectiveTimeout,
                    config.getCircuitBreakerSlidingWindowSize(),
                    config.getCircuitBreakerMinimumNumberOfCalls(),
                    waitDuration,
                    eventBus);
            return new BreakerHolder(effectiveTimeout, breaker);
        });
        return holder == null ? null : holder.breaker;
    }

    private RateLimiter getLimiter(String lingId, InvocationContext ctx) {
        LimiterHolder holder = limiters.get(lingId);
        if (holder != null) {
            Integer governedRateLimit = ctx.governance().getRateLimitPerSecond();
            if (governedRateLimit == null || governedRateLimit <= 0) {
                return holder.limiter;
            }
        }

        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            return null;
        }

        int rateLimit = resolveRateLimit(ctx, runtime.getConfig());
        if (holder != null && holder.rateLimitPerSecond == rateLimit) {
            return holder.limiter;
        }

        holder = limiters.compute(lingId, (id, existing) -> {
            if (existing != null && existing.rateLimitPerSecond == rateLimit) {
                return existing;
            }
            return new LimiterHolder(rateLimit, new TokenBucketRateLimiter(id, rateLimit, rateLimit));
        });
        return holder == null ? null : holder.limiter;
    }

    private int resolveRateLimit(InvocationContext ctx, LingRuntimeConfig config) {
        Integer governedRateLimit = ctx.governance().getRateLimitPerSecond();
        if (governedRateLimit != null && governedRateLimit > 0) {
            return governedRateLimit;
        }

        int rateLimit = config.getRateLimitPerSecond();
        if (rateLimit <= 0) {
            rateLimit = config.getBulkheadMaxConcurrent();
        }
        return Math.max(1, rateLimit);
    }

    /**
     * 灵元卸载时驱逐弹性实例，防止内存泄漏
     */
    public void evict(String lingId) {
        breakers.remove(lingId);
        limiters.remove(lingId);
    }

    /**
     * 受控恢复时仅重置弹性治理状态，不清空限流配置。
     * 这样可以在不丢失运行时预算的前提下，清掉 OPEN / HALF_OPEN 熔断器痕迹。
     */
    public boolean recover(String lingId) {
        return breakers.remove(lingId) != null;
    }

    boolean hasBreaker(String lingId) {
        return breakers.containsKey(lingId);
    }

    boolean hasLimiter(String lingId) {
        return limiters.containsKey(lingId);
    }

    /**
     * 熔断打开时，将灵元宏观状态从 ACTIVE 转为 DEGRADED。
     * 仅在当前状态为 ACTIVE 时才转换，避免重复操作或在 STOPPING 时误触发。
     */
    private void transitionToDegraded(String lingId, String version) {
        if (lingRepository == null || runtimeCoordinator == null)
            return;
        try {
            LingRuntime runtime = lingRepository.getRuntime(lingId);
            if (runtime != null && runtime.currentStatus() == RuntimeStatus.ACTIVE) {
                runtimeCoordinator.transition(lingId, RuntimeStatus.DEGRADED);
                if (governanceMetricsCollector != null) {
                    governanceMetricsCollector.recordCircuitOpened(lingId, version);
                }
                log.warn("[Resilience:{}] Circuit breaker opened, runtime transitioned to DEGRADED", lingId);
            }
        } catch (Exception e) {
            log.debug("[Resilience:{}] Failed to transition to DEGRADED: {}", lingId, e.getMessage());
        }
    }

    /**
     * 熔断器恢复（CLOSED）后，将灵元宏观状态从 DEGRADED 转回 ACTIVE。
     * 仅在当前状态为 DEGRADED 且熔断器状态为 CLOSED 时触发。
     */
    private void tryRecoverFromDegraded(String lingId, CircuitBreaker breaker) {
        if (lingRepository == null || runtimeCoordinator == null)
            return;
        try {
            if (breaker.getState() == CircuitBreaker.State.CLOSED) {
                LingRuntime runtime = lingRepository.getRuntime(lingId);
                if (runtime != null && runtime.currentStatus() == RuntimeStatus.DEGRADED) {
                    runtimeCoordinator.transition(lingId, RuntimeStatus.ACTIVE);
                    if (governanceMetricsCollector != null) {
                        governanceMetricsCollector.recordRecovered(lingId, runtime.getInstancePool().getVersion());
                    }
                    log.info("[Resilience:{}] Circuit breaker recovered, runtime transitioned back to ACTIVE", lingId);
                }
            }
        } catch (Exception e) {
            log.debug("[Resilience:{}] Failed to recover from DEGRADED: {}", lingId, e.getMessage());
        }
    }

    /**
     * 尝试调用降级策略获取响应。
     *
     * @return 降级结果，null 表示无法降级
     */
    private Object tryFallback(String fqsid, FallbackCause cause) {
        FallbackProvider provider = this.fallbackProvider;
        if (provider == null) {
            return null;
        }
        try {
            Object result = provider.fallback(fqsid, cause);
            if (result != null) {
                log.info("[Resilience] Fallback succeeded for cause={}, fqsid={}", cause, fqsid);
            }
            return result;
        } catch (Throwable t) {
            log.warn("[Resilience] Fallback failed for cause={}, fqsid={}: {}", cause, fqsid, t.getMessage());
            return null;
        }
    }

    private static final class LimiterHolder {
        private final int rateLimitPerSecond;
        private final RateLimiter limiter;

        private LimiterHolder(int rateLimitPerSecond, RateLimiter limiter) {
            this.rateLimitPerSecond = rateLimitPerSecond;
            this.limiter = limiter;
        }
    }

    /**
     * 熔断器 holder：缓存 timeoutMs（慢调用阈值），用于检测治理下发后是否需要重建实例。
     * 与 LimiterHolder 模式对称，只跟踪会动态变化的 A 类字段（timeoutMs）。
     */
    private static final class BreakerHolder {
        private final int timeoutMs;
        private final CircuitBreaker breaker;

        private BreakerHolder(int timeoutMs, CircuitBreaker breaker) {
            this.timeoutMs = timeoutMs;
            this.breaker = breaker;
        }
    }
}
