package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.api.storage.LingTransactionContext;
import com.lingframe.api.storage.LingTransactionContext.TransactionSnapshot;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRuntimeConfig;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import com.lingframe.core.spi.ThreadPoolStatsProvider;
import com.lingframe.core.util.NamedThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 线程隔离过滤器。
 * 负责把终端执行放入灵元专属线程池，并消费治理阶段产出的超时决策。
 * <p>
 * ⚠️ 线程池线程默认挂 CORE_CLASSLOADER，单次调用再临时切到目标灵元的 ClassLoader。
 * 如果让线程池常驻线程永久挂住灵元 ClassLoader，灵元卸载后最容易出现“功能没问题，但就是回收不掉”的隐性泄漏。
 */
@Slf4j
public class ThreadIsolationGovernanceFilter implements LingInvocationFilter, ThreadPoolStatsProvider {

    private static final ClassLoader CORE_CLASSLOADER = ThreadIsolationGovernanceFilter.class.getClassLoader();
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();

    private final LingRepository lingRepository;
    private final GovernanceMetricsCollector governanceMetricsCollector;
    private final Map<String, ExecutorHolder> executors = new ConcurrentHashMap<>();

    public ThreadIsolationGovernanceFilter(LingRepository lingRepository) {
        this(lingRepository, null);
    }

    public ThreadIsolationGovernanceFilter(LingRepository lingRepository, GovernanceMetricsCollector governanceMetricsCollector) {
        this.lingRepository = lingRepository;
        this.governanceMetricsCollector = governanceMetricsCollector;
    }

    @Override
    public int getOrder() {
        return FilterPhase.EXECUTION_ISOLATION;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        // 路由去身份化后 FQSID 可能为裸 contractId（无冒号），
        // 隔离池命名以 ctx.getEffectiveLingId() 为准——L0 阶段已解析出真实 lingId
        String lingId = ctx.getEffectiveLingId();
        if (lingId == null || lingId.isEmpty()) {
            return chain.doFilter(ctx);
        }
        if (ctx.execution().getMode().isGovernOnly()) {
            return chain.doFilter(ctx);
        }

        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            return chain.doFilter(ctx);
        }

        LingRuntimeConfig config = runtime.getConfig();
        int timeoutMs = resolveTimeout(ctx, config);
        int maxThreads = resolveMaxThreads(ctx, config);
        ExecutorHolder executorHolder = getExecutorHolder(lingId, maxThreads);
        ExecutorService executor = executorHolder.executor;
        recordThreadBudgetSnapshot(lingId, ctx, executorHolder);
        LingCallContextSnapshot snapshot = LingCallContextSnapshot.capture();
        // 事务上下文跨线程搬运（传播器按调用实例化，杜绝共享状态并发串扰）：
        // 主线程捕获快照（下行携带连接、上行携带 rollbackOnly 信号）
        TransactionContextPropagator txPropagator = new TransactionContextPropagator();
        TransactionSnapshot txSnapshot = txPropagator.capture();
        int inheritedTraceCount = traceCount(ctx);

        // worker 已退出标志：isolatedTask 的 finally 置位（无论正常/异常/中断响应）。
        // 用于超时路径的有界 join——cancel(true) 后 FutureTask 立即进入 INTERRUPTED 状态，
        // future.get() 抛 CancellationException 无法区分「已退出」与「仍在临界区」，
        // 只有任务自身的 finally 才能给出「真正退出」的可信信号
        CountDownLatch workerFinished = new CountDownLatch(1);

        Callable<Object> isolatedTask = () -> {
            InvocationContext child = InvocationContext.obtain();
            InvocationContext previous = child.attach();
            LingCallContextSnapshot previousSnapshot = LingCallContextSnapshot.apply(snapshot);
            // worker 线程重放事务快照：下行连接进入 worker 线程的穿透上下文
            txPropagator.replay(txSnapshot);
            ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
            long beforeCpuTimeNs = currentThreadCpuTime();
            long beforeHeapBytes = usedHeapBytes();
            try {
                child.copyFrom(ctx);
                ClassLoader targetClassLoader = child.resolution().getTargetClassLoader();
                if (targetClassLoader != null) {
                    Thread.currentThread().setContextClassLoader(targetClassLoader);
                }
                return chain.doFilter(child);
            } catch (Exception e) {
                throw e;
            } catch (Error e) {
                throw e;
            } catch (Throwable throwable) {
                throw new ExecutionException(throwable);
            } finally {
                recordBudgetObservations(lingId, child,
                        currentThreadCpuTime() - beforeCpuTimeNs,
                        Math.max(0L, usedHeapBytes() - beforeHeapBytes));
                mergeNewTraces(ctx, child, inheritedTraceCount);
                Thread.currentThread().setContextClassLoader(originalClassLoader);
                // worker finally 恢复：合并语义（worker 期间置位的 rollbackOnly 并入快照上行，
                // 再恢复 worker 线程穿透上下文为执行前状态）——擦除资源、保留信号
                txPropagator.restore(txSnapshot);
                LingCallContextSnapshot.restore(previousSnapshot);
                InvocationContext.detach(previous);
                child.recycle();
                // 置位退出标志（finally 末尾：临界区清理完成后才视为退出）
                workerFinished.countDown();
            }
        };

        Future<Object> future;
        try {
            future = executor.submit(isolatedTask);
        } catch (RejectedExecutionException e) {
            log.warn("[Isolation:{}] Execution rejected because bulkhead is full for {}", lingId, ctx.getServiceFQSID());
            if (governanceMetricsCollector != null) {
                governanceMetricsCollector.recordBulkheadRejected(lingId, ctx.getTargetVersion());
                recordThreadBudgetSnapshot(lingId, ctx, executorHolder);
            }
            throw new LingInvocationException(ctx.getServiceFQSID(), LingInvocationException.ErrorKind.BULKHEAD_FULL, e);
        }

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            // 有界 join：宽限期等待 worker 退出临界区（cancel 后响应中断的驱动会级联 Statement.cancel）。
            // 宽限期超时 → 连接标记 poisoned：跳过 rollback 直接 close 废弃该池连接
            // （未提交写随 close 丢弃，连接池感知废弃后重建）——避免并发 rollback 的未定义行为。
            // ⚠️ 残余风险：宽限期是概率性缓解而非硬保证——worker 阻塞在不可中断 I/O 时，
            // close 与 worker 并发访问同一连接仍属未定义行为，本机制不声称「超时后连接已安全」。
            if (!awaitWorkerExit(workerFinished, config.getAbandonedJoinTimeoutMs())) {
                poisonAbandonedConnections(lingId, ctx);
            }
            log.error("[Isolation:{}] Execution timed out after {} ms for {}", lingId, timeoutMs, ctx.getServiceFQSID());
            if (governanceMetricsCollector != null) {
                governanceMetricsCollector.recordTimeout(lingId, ctx.getTargetVersion());
            }
            throw new LingInvocationException(ctx.getServiceFQSID(), LingInvocationException.ErrorKind.TIMEOUT);
        } catch (ExecutionException e) {
            Throwable cause = unwrapExecutionCause(e.getCause());
            if (cause instanceof LingInvocationException) {
                throw (LingInvocationException) cause;
            }
            throw new LingInvocationException(ctx.getServiceFQSID(), LingInvocationException.ErrorKind.INVOKE_ERROR, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LingInvocationException(ctx.getServiceFQSID(), LingInvocationException.ErrorKind.INTERNAL_ERROR, e);
        } finally {
            // 信号上行：worker 经快照合并回传的 rollbackOnly OR 进主线程穿透上下文
            // （正常返回与异常路径都执行，保证下游回滚意图不丢）
            if (txSnapshot.isRollbackOnly()) {
                LingTransactionContext.setRollbackOnly();
            }
            recordThreadBudgetSnapshot(lingId, ctx, executorHolder);
        }
    }

    private int traceCount(InvocationContext ctx) {
        return ctx.execution().getTraces() == null ? 0 : ctx.execution().getTraces().size();
    }

    private void mergeNewTraces(InvocationContext parent, InvocationContext child, int inheritedTraceCount) {
        if (parent == null || child == null || child.execution().getTraces() == null) {
            return;
        }
        for (int i = inheritedTraceCount; i < child.execution().getTraces().size(); i++) {
            parent.execution().addTrace(child.execution().getTraces().get(i));
        }
    }

    private Throwable unwrapExecutionCause(Throwable cause) {
        Throwable current = cause;
        while (current instanceof ExecutionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? cause : current;
    }

    /**
     * 有界 join：宽限期等待被取消的 worker 退出临界区。
     * <p>
     * 基于 isolatedTask finally 置位的 {@code workerFinished} 标志：cancel(true) 后
     * FutureTask 立即进入 INTERRUPTED 状态，{@code future.get()} 抛 CancellationException
     * 无法区分「已退出」与「仍在临界区」；只有任务自身的 finally 才能给出「真正退出」的可信信号。
     * 宽限期内未置位 → 判定 worker 阻塞在不可中断 I/O，仍在临界区。
     *
     * @param workerFinished worker 退出标志（isolatedTask finally 置位）
     * @param graceMs        宽限期（毫秒）
     * @return true=宽限期内退出；false=宽限期超时，worker 仍在临界区
     */
    private boolean awaitWorkerExit(CountDownLatch workerFinished, int graceMs) {
        try {
            return workerFinished.await(graceMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 主线程被中断：视为宽限期超时（宁可 poisoned 也不冒险并发访问）
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * poisoned 废弃：宽限期超时后跳过 rollback，直接 close 废弃穿透连接。
     * <p>
     * 被放弃的 worker 可能仍占用同一物理连接——直接 close 让连接池感知废弃后重建，
     * 未提交写随 close 丢弃（不会半提交）；残余风险见超时路径注释，不声称「已安全」。
     */
    private void poisonAbandonedConnections(String lingId, InvocationContext ctx) {
        int poisoned = LingTransactionContext.closeAllConnections();
        if (poisoned > 0) {
            log.error("[Isolation:{}] Abandoned worker still in critical section after grace period, "
                    + "poisoned {} penetration connection(s) for {} (residual writes discarded with close)",
                    lingId, poisoned, ctx.getServiceFQSID());
            if (governanceMetricsCollector != null) {
                governanceMetricsCollector.recordConnectionPoisoned(lingId, ctx.getTargetVersion());
            }
        }
    }

    private int resolveTimeout(InvocationContext ctx, LingRuntimeConfig config) {
        Integer governedTimeout = ctx.governance().getTimeoutMs();
        if (governedTimeout != null && governedTimeout >= 0) {
            // 治理阶段已经给出明确 timeout，就不要再让执行阶段自己猜一次
            return governedTimeout;
        }
        return config.getDefaultTimeoutMs();
    }

    private int resolveMaxThreads(InvocationContext ctx, LingRuntimeConfig config) {
        Integer governedMaxThreads = ctx.governance().getMaxConcurrentThreads();
        if (governedMaxThreads != null && governedMaxThreads > 0) {
            return governedMaxThreads;
        }
        return Math.max(1, config.getBulkheadMaxConcurrent());
    }

    private ExecutorHolder getExecutorHolder(String lingId, int maxThreads) {
        return executors.compute(lingId, (id, existing) -> {
            if (existing != null && existing.maxThreads == maxThreads && !existing.executor.isShutdown()) {
                return existing;
            }

            if (existing != null) {
                retireExecutor(id, existing.executor);
            }

            log.debug("[Isolation:{}] Initializing isolated thread pool with maxThreads={}", id, maxThreads);
            return new ExecutorHolder(maxThreads, createExecutor(id, maxThreads));
        });
    }

    private ThreadPoolExecutor createExecutor(String lingId, int maxThreads) {
        return new ThreadPoolExecutor(
                Math.min(2, maxThreads),
                maxThreads,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                NamedThreadFactory.daemon("Ling-Iso-" + lingId, CORE_CLASSLOADER),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private void retireExecutor(String lingId, ExecutorService executor) {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        executor.shutdown();
        log.debug("[Isolation:{}] Retiring isolated thread pool after governance config change", lingId);
    }

    private void shutdownExecutorImmediately(String lingId, ExecutorService executor) {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                log.warn("[Isolation:{}] Thread pool did not terminate within the grace period", lingId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 灵元卸载时驱逐隔离线程池，防止线程泄漏。
     */
    public void evict(String lingId) {
        ExecutorHolder holder = executors.remove(lingId);
        if (holder != null) {
            shutdownExecutorImmediately(lingId, holder.executor);
            log.debug("[Isolation:{}] Evicted isolated thread pool", lingId);
        }
    }

    boolean hasExecutor(String lingId) {
        return executors.containsKey(lingId);
    }

    private void recordThreadBudgetSnapshot(String lingId, InvocationContext ctx, ExecutorHolder executorHolder) {
        if (governanceMetricsCollector == null || executorHolder == null) {
            return;
        }
        ThreadPoolExecutor executor = executorHolder.executor;
        governanceMetricsCollector.recordThreadBudgetSnapshot(
                lingId,
                ctx == null ? null : ctx.getTargetVersion(),
                executor.getActiveCount(),
                executorHolder.maxThreads);
    }

    private void recordBudgetObservations(String lingId, InvocationContext ctx, long cpuTimeNs, long estimatedHeapDeltaBytes) {
        if (governanceMetricsCollector == null || ctx == null) {
            return;
        }
        governanceMetricsCollector.recordCpuBudgetObservation(
                lingId,
                ctx.getTargetVersion(),
                TimeUnit.NANOSECONDS.toMillis(Math.max(0L, cpuTimeNs)),
                ctx.governance().getCpuBudgetMsPerMinute());
        governanceMetricsCollector.recordMemoryBudgetObservation(
                lingId,
                ctx.getTargetVersion(),
                estimatedHeapDeltaBytes,
                ctx.governance().getMemoryBudgetMb());
    }

    private long currentThreadCpuTime() {
        if (!THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported()) {
            return 0L;
        }
        if (!THREAD_MX_BEAN.isThreadCpuTimeEnabled()) {
            try {
                THREAD_MX_BEAN.setThreadCpuTimeEnabled(true);
            } catch (UnsupportedOperationException | SecurityException ignored) {
                return 0L;
            }
        }
        long cpuTime = THREAD_MX_BEAN.getCurrentThreadCpuTime();
        return Math.max(0L, cpuTime);
    }

    private long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    @Override
    public List<ThreadPoolStats> getThreadPoolStats() {
        if (executors.isEmpty()) {
            return Collections.emptyList();
        }
        List<ThreadPoolStats> result = new ArrayList<>(executors.size());
        for (Map.Entry<String, ExecutorHolder> entry : executors.entrySet()) {
            ExecutorHolder holder = entry.getValue();
            ThreadPoolExecutor pool = holder.executor;
            if (pool == null || pool.isShutdown()) {
                continue;
            }
            result.add(new ThreadPoolStats(
                    entry.getKey(),
                    pool.getActiveCount(),
                    pool.getPoolSize(),
                    holder.maxThreads,
                    pool.getQueue() != null ? pool.getQueue().size() : 0,
                    pool.getCompletedTaskCount()));
        }
        return result;
    }

    private static final class ExecutorHolder {
        private final int maxThreads;
        private final ThreadPoolExecutor executor;

        private ExecutorHolder(int maxThreads, ThreadPoolExecutor executor) {
            this.maxThreads = maxThreads;
            this.executor = executor;
        }
    }
}
