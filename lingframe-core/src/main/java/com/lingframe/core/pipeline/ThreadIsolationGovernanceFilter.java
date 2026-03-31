package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRuntimeConfig;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;

/**
 * 线程隔离过滤器。
 * 负责把终端执行放入灵元专属线程池，并消费治理阶段产出的超时决策。
 * <p>
 * ⚠️ 线程池线程默认挂 CORE_CLASSLOADER，单次调用再临时切到目标灵元的 ClassLoader。
 * 如果让线程池常驻线程永久挂住灵元 ClassLoader，灵元卸载后最容易出现“功能没问题，但就是回收不掉”的隐性泄漏。
 */
public class ThreadIsolationGovernanceFilter implements LingInvocationFilter {

    private static final Logger log = LoggerFactory.getLogger(ThreadIsolationGovernanceFilter.class);
    private static final ClassLoader CORE_CLASSLOADER = ThreadIsolationGovernanceFilter.class.getClassLoader();

    private final LingRepository lingRepository;
    private final Map<String, ExecutorHolder> executors = new ConcurrentHashMap<>();

    public ThreadIsolationGovernanceFilter(LingRepository lingRepository) {
        this.lingRepository = lingRepository;
    }

    @Override
    public int getOrder() {
        return FilterPhase.EXECUTION_ISOLATION;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        String fqsid = ctx.getServiceFQSID();
        if (fqsid == null || !fqsid.contains(":")) {
            return chain.doFilter(ctx);
        }
        if (ctx.isGovernOnly()) {
            return chain.doFilter(ctx);
        }

        String lingId = fqsid.split(":", 2)[0];
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            return chain.doFilter(ctx);
        }

        LingRuntimeConfig config = runtime.getConfig();
        int timeoutMs = resolveTimeout(ctx, config);
        int maxThreads = resolveMaxThreads(ctx, config);
        ExecutorService executor = getExecutor(lingId, maxThreads);
        LingCallContextSnapshot snapshot = LingCallContextSnapshot.capture();
        int inheritedTraceCount = traceCount(ctx);

        Callable<Object> isolatedTask = () -> {
            InvocationContext child = InvocationContext.obtain();
            InvocationContext previous = child.attach();
            LingCallContextSnapshot previousSnapshot = LingCallContextSnapshot.apply(snapshot);
            ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
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
                mergeNewTraces(ctx, child, inheritedTraceCount);
                Thread.currentThread().setContextClassLoader(originalClassLoader);
                LingCallContextSnapshot.restore(previousSnapshot);
                InvocationContext.detach(previous);
                child.recycle();
            }
        };

        Future<Object> future;
        try {
            future = executor.submit(isolatedTask);
        } catch (RejectedExecutionException e) {
            log.warn("[Isolation:{}] Execution rejected because bulkhead is full for {}", lingId, fqsid);
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.RATE_LIMITED, e);
        }

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("[Isolation:{}] Execution timed out after {} ms for {}", lingId, timeoutMs, fqsid);
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.TIMEOUT);
        } catch (ExecutionException e) {
            Throwable cause = unwrapExecutionCause(e.getCause());
            if (cause instanceof LingInvocationException) {
                throw (LingInvocationException) cause;
            }
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.INVOKE_ERROR, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.INTERNAL_ERROR, e);
        }
    }

    private int traceCount(InvocationContext ctx) {
        return ctx.getTraces() == null ? 0 : ctx.getTraces().size();
    }

    private void mergeNewTraces(InvocationContext parent, InvocationContext child, int inheritedTraceCount) {
        if (parent == null || child == null || child.getTraces() == null) {
            return;
        }
        for (int i = inheritedTraceCount; i < child.getTraces().size(); i++) {
            parent.addTrace(child.getTraces().get(i));
        }
    }

    private Throwable unwrapExecutionCause(Throwable cause) {
        Throwable current = cause;
        while (current instanceof ExecutionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? cause : current;
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

    private ExecutorService getExecutor(String lingId, int maxThreads) {
        ExecutorHolder holder = executors.compute(lingId, (id, existing) -> {
            if (existing != null && existing.maxThreads == maxThreads && !existing.executor.isShutdown()) {
                return existing;
            }

            if (existing != null) {
                retireExecutor(id, existing.executor);
            }

            log.debug("[Isolation:{}] Initializing isolated thread pool with maxThreads={}", id, maxThreads);
            return new ExecutorHolder(maxThreads, createExecutor(id, maxThreads));
        });
        return holder.executor;
    }

    private ExecutorService createExecutor(String lingId, int maxThreads) {
        return new ThreadPoolExecutor(
                Math.min(2, maxThreads),
                maxThreads,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new ThreadFactory() {
                    private int counter = 0;

                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable, "Ling-Iso-" + lingId + "-" + (++counter));
                        thread.setDaemon(true);
                        // ⚠️ 常驻线程只挂核心 ClassLoader；单次任务内再临时切换，避免线程把灵元 ClassLoader 挂死
                        thread.setContextClassLoader(CORE_CLASSLOADER);
                        return thread;
                    }
                },
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

    private static final class ExecutorHolder {
        private final int maxThreads;
        private final ExecutorService executor;

        private ExecutorHolder(int maxThreads, ExecutorService executor) {
            this.maxThreads = maxThreads;
            this.executor = executor;
        }
    }
}
