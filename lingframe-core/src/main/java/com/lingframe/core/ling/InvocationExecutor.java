package com.lingframe.core.ling;

import com.lingframe.core.exception.CallNotPermittedException;
import com.lingframe.core.governance.DefaultTransactionVerifier;
import com.lingframe.core.invoker.FastLingServiceInvoker;
import com.lingframe.core.monitor.TraceContext;
import com.lingframe.core.ling.event.RuntimeEvent;
import com.lingframe.core.ling.event.RuntimeEventBus;
import com.lingframe.core.resilience.CircuitBreaker;
import com.lingframe.core.resilience.RateLimiter;
import com.lingframe.core.resilience.SlidingWindowCircuitBreaker;
import com.lingframe.core.resilience.TokenBucketRateLimiter;
import com.lingframe.core.spi.LingServiceInvoker;
import com.lingframe.core.spi.ThreadLocalPropagator;
import com.lingframe.core.exception.InvocationException;
import com.lingframe.core.spi.TransactionVerifier;
import lombok.NonNull;
import lombok.Setter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import com.lingframe.api.context.LingContextHolder;

/**
 * 服务调用执行器
 * 职责：线程隔离、超时控制、上下文传播、舱壁隔离
 */
@Slf4j
public class InvocationExecutor {

    private final String lingId;
    private final ExecutorService executor;
    private final Semaphore bulkhead;
    private final List<ThreadLocalPropagator> propagators;
    private final TransactionVerifier transactionVerifier;
    private final LingServiceInvoker invoker;
    private final int timeoutMs;
    private final int acquireTimeoutMs;

    @Setter
    private RuntimeEventBus eventBus; // 可选，用于发布调用事件（内部）

    @Setter
    private com.lingframe.core.event.EventBus monitorBus; // 可选，用于发布监控事件（外部）

    public InvocationExecutor(String lingId,
                              ExecutorService executor,
                              LingServiceInvoker invoker,
                              TransactionVerifier transactionVerifier,
                              List<ThreadLocalPropagator> propagators,
                              int bulkheadPermits,
                              int timeoutMs,
                              int acquireTimeoutMs) {
        this.lingId = lingId;
        this.executor = executor;
        this.invoker = invoker;
        this.transactionVerifier = transactionVerifier != null ? transactionVerifier : new DefaultTransactionVerifier();
        ;
        this.propagators = propagators != null ? new ArrayList<>(propagators) : new ArrayList<>();
        this.bulkhead = new Semaphore(bulkheadPermits);
        this.timeoutMs = timeoutMs;
        this.acquireTimeoutMs = acquireTimeoutMs;
    }

    /**
     * 使用配置构造
     */
    public InvocationExecutor(String lingId,
                              ExecutorService executor,
                              LingServiceInvoker invoker,
                              TransactionVerifier transactionVerifier,
                              List<ThreadLocalPropagator> propagators,
                              LingRuntimeConfig config) {
        this(lingId, executor, invoker, transactionVerifier, propagators,
                config.getBulkheadMaxConcurrent(),
                config.getDefaultTimeoutMs(),
                config.getBulkheadAcquireTimeoutMs());
    }

    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    private CircuitBreaker getOrGenericCircuitBreaker(String fqsid) {
        return circuitBreakers.computeIfAbsent(fqsid,
                k -> new SlidingWindowCircuitBreaker(
                        k,
                        50, // 50% 失败率
                        50, // 50% 慢调用率
                        2000, // 2s 算慢调用
                        100, // 窗口大小 100
                        10, // 最小请求数 10
                        5000, // 熔断后等待 5s
                        monitorBus // 注入 System EventBus
                ));
    }

    private final Map<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

    private RateLimiter getOrGenericRateLimiter(String fqsid) {
        return rateLimiters.computeIfAbsent(fqsid,
                k -> new TokenBucketRateLimiter(
                        k,
                        500.0, // 500 QPS
                        500.0 // Burst capacity
                ));
    }

    public Object execute(LingInstance instance,
                          ServiceRegistry.InvokableService service,
                          Object[] args,
                          String callerLingId,
                          String fqsid) throws Exception {

        // 发布调用开始事件
        publishEvent(new RuntimeEvent.InvocationStarted(lingId, fqsid, callerLingId));

        long startTime = System.currentTimeMillis();
        boolean success = false;

        try {
            Object result = doExecute(instance, service, args, callerLingId, fqsid);
            success = true;
            return result;
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            publishEvent(new RuntimeEvent.InvocationCompleted(lingId, fqsid, duration, success));
        }
    }

    private void publishEvent(RuntimeEvent event) {
        if (eventBus != null) {
            eventBus.publish(event);
        }
    }

    // 舱壁拒绝时
    private void onBulkheadRejected(String fqsid, String callerLingId) {
        publishEvent(new RuntimeEvent.InvocationRejected(lingId, fqsid, "Bulkhead full"));
    }

    /**
     * 执行服务调用
     *
     * @param instance       目标实例
     * @param service        可调用服务
     * @param args           参数
     * @param callerLingId 调用方单元ID（用于日志）
     * @param fqsid          服务ID（用于日志）
     * @return 调用结果
     */
    public Object doExecute(LingInstance instance,
                            ServiceRegistry.InvokableService service,
                            Object[] args,
                            String callerLingId,
                            String fqsid) throws Exception {

        // 判断是否需要同步执行（事务场景）
        boolean isTx = transactionVerifier.isTransactional(
                service.method(),
                service.bean().getClass());

        if (isTx) {
            // 同步模式：直接在当前线程执行，保持事务传播
            return executeInternal(instance, service, args);
        }

        // 异步模式：线程隔离执行
        return executeAsync(instance, service, args, callerLingId, fqsid, null);
    }

    /**
     * 同步执行（事务场景）
     */
    public Object executeSync(LingInstance instance,
                              ServiceRegistry.InvokableService service,
                              Object[] args) throws Exception {
        return executeInternal(instance, service, args);
    }

    /**
     * 异步执行（线程隔离）
     */
    public Object executeAsync(LingInstance instance,
                               ServiceRegistry.InvokableService service,
                               Object[] args,
                               String callerLingId,
                               String fqsid,
                               Integer timeoutOverride) throws Exception {

        // 1. 限流检查
        RateLimiter limiter = getOrGenericRateLimiter(fqsid);
        if (!limiter.tryAcquire()) {
            throw new CallNotPermittedException(fqsid, "RateLimit exceeded");
        }

        // 2. 熔断检查
        CircuitBreaker breaker = getOrGenericCircuitBreaker(fqsid);
        if (!breaker.tryAcquirePermission()) {
            throw new CallNotPermittedException(fqsid, "CircuitBreaker is OPEN");
        }

        // 捕获上下文快照
        ContextSnapshot snapshot = captureContext();

        // 创建异步任务
        Callable<Object> task = () -> {
            ContextSnapshot.Scope scope = null;
            try {
                // 重放上下文
                scope = snapshot.replay();
                return executeInternal(instance, service, args);
            } finally {
                // 清理上下文
                if (scope != null) {
                    scope.close();
                }
            }
        };

        // 获取舱壁许可
        if (!bulkhead.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS)) {
            throw new RejectedExecutionException(
                    "Ling [" + lingId + "] is busy (Bulkhead full). FQSID: " + fqsid);
        }

        long startTime = System.nanoTime();
        try {
            Future<Object> future = executor.submit(task);
            Object result = waitForResult(future, fqsid, callerLingId, timeoutOverride);

            // 成功记录
            long duration = System.nanoTime() - startTime;
            breaker.onSuccess(duration, TimeUnit.NANOSECONDS);

            return result;
        } catch (Exception e) {
            // 失败记录
            long duration = System.nanoTime() - startTime;
            breaker.onError(duration, TimeUnit.NANOSECONDS, e);
            throw e;
        } finally {
            // 确保信号量释放
            try {
                bulkhead.release();
            } catch (IllegalStateException e) {
                log.warn("[{}] Failed to release bulkhead permit for FQSID: {}", lingId, fqsid, e);
            }
        }
    }

    /**
     * 等待异步结果
     */
    private Object waitForResult(Future<Object> future, String fqsid, String callerLingId, Integer timeoutOverride)
            throws Exception {
        int timeout = (timeoutOverride != null && timeoutOverride > 0) ? timeoutOverride : this.timeoutMs;
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("[{}] Execution timeout ({}ms). FQSID={}, Caller={}",
                    lingId, timeoutMs, fqsid, callerLingId);
            throw new TimeoutException("Ling execution timeout: " + fqsid);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new InvocationException("Ling execution failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InvocationException("Ling execution interrupted", e);
        }
    }

    /**
     * 内部执行逻辑
     */
    private Object executeInternal(LingInstance instance,
                                   ServiceRegistry.InvokableService service,
                                   Object[] args) throws Exception {

        // 1. 捕获当前 TCCL
        Thread currentThread = Thread.currentThread();
        ClassLoader originalClassLoader = currentThread.getContextClassLoader();

        // 2. 设置单元 TCCL (如果容器可用)
        if (instance.getContainer() != null && instance.getContainer().getClassLoader() != null) {
            currentThread.setContextClassLoader(instance.getContainer().getClassLoader());
        }

        try {
            if (invoker instanceof FastLingServiceInvoker) {
                FastLingServiceInvoker fast = (FastLingServiceInvoker) invoker;
                return fast.invokeFast(instance, service.methodHandle(), args);
            }
            return invoker.invoke(instance, service.bean(), service.method(), args);
        } catch (Throwable t) {
            if (t instanceof Exception) {
                throw (Exception) t;
            } else if (t instanceof Error) {
                throw (Error) t;
            } else {
                throw new InvocationException("Execution failed", t);
            }
        } finally {
            // 3. 恢复原始 TCCL
            currentThread.setContextClassLoader(originalClassLoader);
        }
    }

    /**
     * 捕获当前线程上下文
     */
    private ContextSnapshot captureContext() {
        String traceId = TraceContext.get();
        Object[] snapshots = new Object[propagators.size()];
        for (int i = 0; i < propagators.size(); i++) {
            snapshots[i] = propagators.get(i).capture();
        }
        return new ContextSnapshot(traceId, snapshots, propagators);
    }

    /**
     * 获取当前可用许可数
     */
    public int getAvailablePermits() {
        return bulkhead.availablePermits();
    }

    /**
     * 获取等待许可的线程数
     */
    public int getQueueLength() {
        return bulkhead.getQueueLength();
    }

    /**
     * 获取统计信息
     */
    public ExecutorStats getStats() {
        return new ExecutorStats(
                bulkhead.availablePermits(),
                bulkhead.getQueueLength(),
                timeoutMs,
                acquireTimeoutMs);
    }

    /**
     * 关闭执行器，清理内部引用并强制终止线程池
     * <p>
     * 每个单元拥有独立线程池，卸载时 shutdownNow() 强制释放
     * Lambda 闭包中捕获的 LingInstance → ClassLoader 引用链。
     * </p>
     */
    public void shutdown() {
        this.eventBus = null;
        this.propagators.clear();
        // 强制终止单元独立线程池，释放所有闭包引用
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
        log.debug("[{}] InvocationExecutor shutdown, thread pool terminated", lingId);
    }

    // ==================== 内部类 ====================

    /**
     * 上下文快照
     */
    private static class ContextSnapshot {
        private final String traceId;
        private final String lingId;
        private final Map<String, String> labels;
        private final Object[] snapshots;
        private final List<ThreadLocalPropagator> propagators;

        ContextSnapshot(String traceId, Object[] snapshots, List<ThreadLocalPropagator> propagators) {
            this.traceId = traceId;
            this.lingId = LingContextHolder.get();
            this.labels = LingContextHolder.getLabels() != null
                    ? LingContextHolder.getLabels()
                    : Collections.emptyMap();
            this.snapshots = snapshots;
            this.propagators = propagators;
        }

        /**
         * 在子线程重放上下文
         */
        Scope replay() {
            // 设置 TraceId
            if (traceId != null) {
                TraceContext.setTraceId(traceId);
            }

            // 设置 LingContext
            String oldLingId = LingContextHolder.get();
            Map<String, String> oldLabels = LingContextHolder.getLabels();

            LingContextHolder.set(lingId);
            LingContextHolder.setLabels(labels);

            // 重放其他上下文
            Object[] backups = new Object[propagators.size()];
            for (int i = 0; i < propagators.size(); i++) {
                backups[i] = propagators.get(i).replay(snapshots[i]);
            }

            return new Scope(backups, propagators, oldLingId, oldLabels);
        }

        /**
         * 作用域（用于自动清理）
         */
        static class Scope implements AutoCloseable {
            private final Object[] backups;
            private final List<ThreadLocalPropagator> propagators;
            private final String oldLingId;
            private final java.util.Map<String, String> oldLabels;

            Scope(Object[] backups, List<ThreadLocalPropagator> propagators, String oldLingId,
                  java.util.Map<String, String> oldLabels) {
                this.backups = backups;
                this.propagators = propagators;
                this.oldLingId = oldLingId;
                this.oldLabels = oldLabels;
            }

            @Override
            public void close() {
                // 恢复原始上下文
                for (int i = 0; i < propagators.size(); i++) {
                    propagators.get(i).restore(backups[i]);
                }
                // 清理 TraceId
                TraceContext.clear();

                // 恢复 LingContext
                if (oldLingId != null) {
                    LingContextHolder.set(oldLingId);
                    LingContextHolder.setLabels(oldLabels);
                } else {
                    LingContextHolder.clear();
                }
            }
        }
    }

    /**
     * 执行器统计信息
     */
    @Value
    public static class ExecutorStats {
        int availablePermits;
        int queueLength;
        int timeoutMs;
        int acquireTimeoutMs;

        public int availablePermits() {
            return availablePermits;
        }

        public int queueLength() {
            return queueLength;
        }

        public int timeoutMs() {
            return timeoutMs;
        }

        public int acquireTimeoutMs() {
            return acquireTimeoutMs;
        }

        @Override
        @NonNull
        public String toString() {
            return String.format(
                    "ExecutorStats{available=%d, queued=%d, timeout=%dms}",
                    availablePermits, queueLength, timeoutMs);
        }
    }
}