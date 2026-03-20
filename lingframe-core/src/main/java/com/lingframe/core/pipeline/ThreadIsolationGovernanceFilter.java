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
    private final Map<String, ExecutorService> executors = new ConcurrentHashMap<>();

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
        ExecutorService executor = getExecutor(lingId, config);

        Callable<Object> isolatedTask = InvocationContext.wrap(() -> {
            ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
            ClassLoader targetClassLoader = ctx.resolution().getTargetClassLoader();
            if (targetClassLoader != null) {
                // 进入真实执行前，再把工作线程临时切到目标灵元的类型宇宙
                Thread.currentThread().setContextClassLoader(targetClassLoader);
            }
            try {
                return chain.doFilter(ctx);
            } catch (Exception e) {
                throw e;
            } catch (Error e) {
                throw e;
            } catch (Throwable throwable) {
                throw new ExecutionException(throwable);
            } finally {
                Thread.currentThread().setContextClassLoader(originalClassLoader);
            }
        });

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
            Throwable cause = e.getCause();
            if (cause instanceof LingInvocationException) {
                throw (LingInvocationException) cause;
            }
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.INVOKE_ERROR, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.INTERNAL_ERROR, e);
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

    private ExecutorService getExecutor(String lingId, LingRuntimeConfig config) {
        return executors.computeIfAbsent(lingId, id -> {
            int maxThreads = config.getBulkheadMaxConcurrent();
            log.debug("[Isolation:{}] Initializing isolated thread pool with maxThreads={}", id, maxThreads);
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
                            Thread thread = new Thread(runnable, "Ling-Iso-" + id + "-" + (++counter));
                            thread.setDaemon(true);
                            // ⚠️ 常驻线程只挂核心 ClassLoader；单次任务内再临时切换，避免线程把灵元 ClassLoader 挂死
                            thread.setContextClassLoader(CORE_CLASSLOADER);
                            return thread;
                        }
                    },
                    new ThreadPoolExecutor.AbortPolicy());
        });
    }

    /**
     * 灵元卸载时驱逐隔离线程池，防止线程泄漏。
     */
    public void evict(String lingId) {
        ExecutorService executor = executors.remove(lingId);
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                    log.warn("[Isolation:{}] Thread pool did not terminate within the grace period", lingId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.debug("[Isolation:{}] Evicted isolated thread pool", lingId);
        }
    }
}
