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
            // 倒挂修复：治理拒绝不得计入熔断失败率。
            // 下游 Filter（如 ThreadIsolationGovernanceFilter 舱满 BULKHEAD_FULL、权限
            // SECURITY_REJECTED）抛出的 LingInvocationException 是平台层准入拦截，不是下游
            // 实例故障——若喂 breaker.onError，舱满/权限误配会累计失败率、误开熔断器，
            // 形成「治理拒绝反噬治理」的倒挂。判定复用 ErrorKind.isGovernanceRejection()：
            // 仅治理拒绝被排除喂料，INVOKE_ERROR / TIMEOUT / CLASSLOADER_ERROR 等真实下游
            // 故障仍正常计入失败率，熔断判定准确性不受影响。
            if (breaker != null && !(t instanceof LingInvocationException
                    && ((LingInvocationException) t).getKind().isGovernanceRejection())) {
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
     * <p>
     * 配置指纹 = A 类 timeoutMs + B 类（failureRate/slowCallRate/slidingWindow/minimumCalls/
     * waitDuration）。任一指纹变化即用 compute 原子重建熔断器实例——包括配置中心热刷
     * {@code LingRuntime.updateConfig} 导致的引用替换（B 类参数此前仅在 timeoutMs 变化时
     * 被顺带消费，单独热刷阈值/窗口会静默失效）。
     */
    private CircuitBreaker getBreaker(String lingId, InvocationContext ctx) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            return null;
        }
        LingRuntimeConfig config = runtime.getConfig();
        // 读 ctx.governance()（预填充后有值），回退 config 静态默认值
        Integer governedTimeout = ctx.governance().getTimeoutMs();
        int effectiveTimeout = governedTimeout != null ? governedTimeout : config.getDefaultTimeoutMs();
        long effectiveWaitDurationMs = config.getCircuitBreakerWaitDurationInOpenStateMs() > 0
                ? config.getCircuitBreakerWaitDurationInOpenStateMs()
                : effectiveTimeout * 10L;

        // 指纹命中（A/B 类参数均未变化）直接复用，避免每次请求重建；
        // 任一参数变化即重建，使热刷后的新参数真实生效。
        BreakerHolder holder = breakers.get(lingId);
        if (holder != null && holder.matches(effectiveTimeout, config, effectiveWaitDurationMs)) {
            return holder.breaker;
        }

        holder = breakers.compute(lingId, (id, existing) -> {
            if (existing != null && existing.matches(effectiveTimeout, config, effectiveWaitDurationMs)) {
                return existing;
            }
            CircuitBreaker breaker = new SlidingWindowCircuitBreaker(
                    id,
                    config.getCircuitBreakerFailureRateThreshold(),
                    config.getCircuitBreakerSlowCallRateThreshold(),
                    effectiveTimeout,
                    config.getCircuitBreakerSlidingWindowSize(),
                    config.getCircuitBreakerMinimumNumberOfCalls(),
                    effectiveWaitDurationMs,
                    eventBus);
            return new BreakerHolder(
                    effectiveTimeout,
                    config.getCircuitBreakerFailureRateThreshold(),
                    config.getCircuitBreakerSlowCallRateThreshold(),
                    config.getCircuitBreakerSlidingWindowSize(),
                    config.getCircuitBreakerMinimumNumberOfCalls(),
                    effectiveWaitDurationMs,
                    breaker);
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

    /**
     * 回灌一次真实调用结果到灵元的熔断器（非治理路径）。
     * <p>
     * agent / 外部调用方在 GOVERN_ONLY 模式下无法让 {@link #doFilter} 内部走完
     * `chain.doFilter(ctx)` 的失败回灌（TerminalInvokerFilter 恒 return null、不抛异常），
     * 导致真实业务失败喂不进熔断器统计、熔断永不 OPEN。本方法提供补救入口：
     * 由调用方在真实执行返回后显式上报成败。
     * <p>
     * 只上报已存在的熔断器（不存在则忽略），不在此触发病态创建；
     * 行败判定（是否为下游可用性失败）由调用方裁决，本方法照单全收。
     */
    void reportOutcome(String lingId, boolean success, long durationNanos, Throwable error) {
        if (lingId == null || lingId.isEmpty()) {
            return;
        }
        BreakerHolder holder = breakers.get(lingId);
        if (holder == null) {
            return;
        }
        if (success) {
            holder.breaker.onSuccess(durationNanos, TimeUnit.NANOSECONDS);
        } else {
            holder.breaker.onError(durationNanos, TimeUnit.NANOSECONDS, error);
        }
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
                        String version = runtime.getInstancePool() != null ? runtime.getInstancePool().getVersion() : "virtual";
                        governanceMetricsCollector.recordRecovered(lingId, version);
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
     * 熔断器 holder：缓存完整配置指纹（A 类 timeoutMs + B 类 failureRate/slowCallRate/
     * slidingWindow/minimumCalls/waitDuration），任一变化即失配触发重建。
     * <p>
     * 重建的副作用：新熔断器以全新 CLOSED 状态启动（放弃旧 OPEN/半开/窗口统计），
     * 语义等价于运维主动调整参数 → 治理器以新参数重新收敛，与限流器重建行为一致。
     */
    private static final class BreakerHolder {
        private final int timeoutMs;
        private final int failureRateThreshold;
        private final int slowCallRateThreshold;
        private final int slidingWindowSize;
        private final int minimumNumberOfCalls;
        private final long effectiveWaitDurationMs;
        private final CircuitBreaker breaker;

        private BreakerHolder(int timeoutMs, int failureRateThreshold, int slowCallRateThreshold,
                              int slidingWindowSize, int minimumNumberOfCalls,
                              long effectiveWaitDurationMs, CircuitBreaker breaker) {
            this.timeoutMs = timeoutMs;
            this.failureRateThreshold = failureRateThreshold;
            this.slowCallRateThreshold = slowCallRateThreshold;
            this.slidingWindowSize = slidingWindowSize;
            this.minimumNumberOfCalls = minimumNumberOfCalls;
            this.effectiveWaitDurationMs = effectiveWaitDurationMs;
            this.breaker = breaker;
        }

        private boolean matches(int timeoutMs, LingRuntimeConfig config, long effectiveWaitDurationMs) {
            return this.timeoutMs == timeoutMs
                    && this.failureRateThreshold == config.getCircuitBreakerFailureRateThreshold()
                    && this.slowCallRateThreshold == config.getCircuitBreakerSlowCallRateThreshold()
                    && this.slidingWindowSize == config.getCircuitBreakerSlidingWindowSize()
                    && this.minimumNumberOfCalls == config.getCircuitBreakerMinimumNumberOfCalls()
                    && this.effectiveWaitDurationMs == effectiveWaitDurationMs;
        }
    }
}
