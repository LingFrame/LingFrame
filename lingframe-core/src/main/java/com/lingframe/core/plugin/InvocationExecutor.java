package com.lingframe.core.plugin;

import com.lingframe.core.governance.DefaultTransactionVerifier;
import com.lingframe.core.invoker.FastPluginServiceInvoker;
import com.lingframe.core.monitor.TraceContext;
import com.lingframe.core.plugin.event.RuntimeEvent;
import com.lingframe.core.plugin.event.RuntimeEventBus;
import com.lingframe.core.spi.PluginServiceInvoker;
import com.lingframe.core.spi.ThreadLocalPropagator;
import com.lingframe.core.exception.InvocationException;
import com.lingframe.core.spi.TransactionVerifier;
import jakarta.annotation.Nonnull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 服务调用执行器
 * 职责：线程隔离、超时控制、上下文传播、舱壁隔离
 */
@Slf4j
public class InvocationExecutor {

    private final String pluginId;
    private final ExecutorService executor;
    private final Semaphore bulkhead;
    private final List<ThreadLocalPropagator> propagators;
    private final TransactionVerifier transactionVerifier;
    private final PluginServiceInvoker invoker;
    private final int timeoutMs;
    private final int acquireTimeoutMs;

    @Setter
    private RuntimeEventBus eventBus; // 可选，用于发布调用事件

    public InvocationExecutor(String pluginId,
            ExecutorService executor,
            PluginServiceInvoker invoker,
            TransactionVerifier transactionVerifier,
            List<ThreadLocalPropagator> propagators,
            int bulkheadPermits,
            int timeoutMs,
            int acquireTimeoutMs) {
        this.pluginId = pluginId;
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
    public InvocationExecutor(String pluginId,
            ExecutorService executor,
            PluginServiceInvoker invoker,
            TransactionVerifier transactionVerifier,
            List<ThreadLocalPropagator> propagators,
            PluginRuntimeConfig config) {
        this(pluginId, executor, invoker, transactionVerifier, propagators,
                config.getBulkheadMaxConcurrent(),
                config.getDefaultTimeoutMs(),
                config.getBulkheadAcquireTimeoutMs());
    }

    public Object execute(PluginInstance instance,
            ServiceRegistry.InvokableService service,
            Object[] args,
            String callerPluginId,
            String fqsid) throws Exception {

        // 发布调用开始事件
        publishEvent(new RuntimeEvent.InvocationStarted(pluginId, fqsid, callerPluginId));

        long startTime = System.currentTimeMillis();
        boolean success = false;

        try {
            Object result = doExecute(instance, service, args, callerPluginId, fqsid);
            success = true;
            return result;
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            publishEvent(new RuntimeEvent.InvocationCompleted(pluginId, fqsid, duration, success));
        }
    }

    private void publishEvent(RuntimeEvent event) {
        if (eventBus != null) {
            eventBus.publish(event);
        }
    }

    // 舱壁拒绝时
    private void onBulkheadRejected(String fqsid, String callerPluginId) {
        publishEvent(new RuntimeEvent.InvocationRejected(pluginId, fqsid, "Bulkhead full"));
    }

    /**
     * 执行服务调用
     *
     * @param instance       目标实例
     * @param service        可调用服务
     * @param args           参数
     * @param callerPluginId 调用方插件ID（用于日志）
     * @param fqsid          服务ID（用于日志）
     * @return 调用结果
     */
    public Object doExecute(PluginInstance instance,
            ServiceRegistry.InvokableService service,
            Object[] args,
            String callerPluginId,
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
        return executeAsync(instance, service, args, callerPluginId, fqsid);
    }

    /**
     * 同步执行（事务场景）
     */
    public Object executeSync(PluginInstance instance,
            ServiceRegistry.InvokableService service,
            Object[] args) throws Exception {
        return executeInternal(instance, service, args);
    }

    /**
     * 异步执行（线程隔离）
     */
    public Object executeAsync(PluginInstance instance,
            ServiceRegistry.InvokableService service,
            Object[] args,
            String callerPluginId,
            String fqsid) throws Exception {

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
                    "Plugin [" + pluginId + "] is busy (Bulkhead full). FQSID: " + fqsid);
        }

        // 提交任务并等待结果
        try {
            Future<Object> future = executor.submit(task);
            return waitForResult(future, fqsid, callerPluginId);
        } finally {
            // 确保信号量释放，并妥善处理释放过程中的异常
            // 防止 release() 抛出的异常覆盖原始异常
            try {
                bulkhead.release();
            } catch (IllegalStateException e) {
                // 记录日志但不抛出，防止掩盖原始异常
                log.warn("[{}] Failed to release bulkhead permit for FQSID: {}", pluginId, fqsid, e);
            }
        }
    }

    /**
     * 等待异步结果
     */
    private Object waitForResult(Future<Object> future, String fqsid, String callerPluginId) throws Exception {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("[{}] Execution timeout ({}ms). FQSID={}, Caller={}",
                    pluginId, timeoutMs, fqsid, callerPluginId);
            throw new TimeoutException("Plugin execution timeout: " + fqsid);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new InvocationException("Plugin execution failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InvocationException("Plugin execution interrupted", e);
        }
    }

    /**
     * 内部执行逻辑
     */
    private Object executeInternal(PluginInstance instance,
            ServiceRegistry.InvokableService service,
            Object[] args) throws Exception {
        try {
            if (invoker instanceof FastPluginServiceInvoker fast) {
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

    // ==================== 内部类 ====================

    /**
     * 上下文快照
     */
    private static class ContextSnapshot {
        private final String traceId;
        private final Object[] snapshots;
        private final List<ThreadLocalPropagator> propagators;

        ContextSnapshot(String traceId, Object[] snapshots, List<ThreadLocalPropagator> propagators) {
            this.traceId = traceId;
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

            // 重放其他上下文
            Object[] backups = new Object[propagators.size()];
            for (int i = 0; i < propagators.size(); i++) {
                backups[i] = propagators.get(i).replay(snapshots[i]);
            }

            return new Scope(backups, propagators);
        }

        /**
         * 作用域（用于自动清理）
         */
        static class Scope implements AutoCloseable {
            private final Object[] backups;
            private final List<ThreadLocalPropagator> propagators;

            Scope(Object[] backups, List<ThreadLocalPropagator> propagators) {
                this.backups = backups;
                this.propagators = propagators;
            }

            @Override
            public void close() {
                // 恢复原始上下文
                for (int i = 0; i < propagators.size(); i++) {
                    propagators.get(i).restore(backups[i]);
                }
                // 清理 TraceId
                TraceContext.clear();
            }
        }
    }

    /**
     * 执行器统计信息
     */
    public record ExecutorStats(
            int availablePermits,
            int queueLength,
            int timeoutMs,
            int acquireTimeoutMs) {
        @Override
        @Nonnull
        public String toString() {
            return String.format(
                    "ExecutorStats{available=%d, queued=%d, timeout=%dms}",
                    availablePermits, queueLength, timeoutMs);
        }
    }
}