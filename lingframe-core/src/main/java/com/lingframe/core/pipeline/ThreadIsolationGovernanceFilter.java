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
 * 线程隔离与真超时治理过滤器
 * <p>
 * 职责：
 * 1. 提供插件沙箱的最后一道防线：线程物理隔离。
 * 2. 避免 Web 容器宿主线程被恶意/阻塞的插件耗尽。
 * 3. 施加真正的 Future.get(timeout) 本地超时打断惩罚。
 */
public class ThreadIsolationGovernanceFilter implements LingInvocationFilter {
    private static final Logger log = LoggerFactory.getLogger(ThreadIsolationGovernanceFilter.class);

    private final LingRepository lingRepository;
    private final Map<String, ExecutorService> executors = new ConcurrentHashMap<>();

    public ThreadIsolationGovernanceFilter(LingRepository lingRepository) {
        this.lingRepository = lingRepository;
    }

    @Override
    public int getOrder() {
        return FilterPhase.ISOLATION;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        String fqsid = ctx.getServiceFQSID();
        if (fqsid == null || !fqsid.contains(":")) {
            return chain.doFilter(ctx);
        }

        String lingId = fqsid.split(":")[0];
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            return chain.doFilter(ctx);
        }

        LingRuntimeConfig config = runtime.getConfig();
        int timeoutMs = config.getDefaultTimeoutMs();

        // 获取或创建灵元专属隔离线程池
        ExecutorService executor = getExecutor(lingId, config);

        // 利用 InvocationContext 的能力捕获主线程快照并包裹为子线程任务
        Callable<Object> isolatedTask = InvocationContext.wrap(() -> {
            try {
                return chain.doFilter(ctx);
            } catch (Exception e) {
                throw e;
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                throw new ExecutionException(t);
            }
        });

        Future<Object> future;
        try {
            future = executor.submit(isolatedTask);
        } catch (RejectedExecutionException e) {
            log.warn("[Isolation:{}] Execution rejected (bulkhead full) for FQSID={}", lingId, fqsid);
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.RATE_LIMITED, e);
        }

        try {
            // 真超时阻塞等待
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 超时直接取消子线程（发起 interrupt），保全宿主线程
            future.cancel(true);
            log.error("[Isolation:{}] Execution timeout ({}ms). Task cancelled for FQSID={}", lingId, timeoutMs, fqsid);
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

    private ExecutorService getExecutor(String lingId, LingRuntimeConfig config) {
        return executors.computeIfAbsent(lingId, id -> {
            int maxThreads = config.getBulkheadMaxConcurrent();
            log.debug("[Isolation:{}] Initializing isolated thread pool, maxThreads={}", id, maxThreads);
            return new ThreadPoolExecutor(
                    Math.min(2, maxThreads), // 核心线程
                    maxThreads, // 最大隔离线程
                    60L, TimeUnit.SECONDS,
                    new SynchronousQueue<>(), // 拒绝排队，交由前置 RateLimiter/Bulkhead 限流器拦截
                    new ThreadFactory() {
                        private int count = 0;

                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "Ling-Iso-" + id + "-" + (++count));
                            t.setDaemon(true);
                            return t;
                        }
                    },
                    new ThreadPoolExecutor.AbortPolicy());
        });
    }

    /**
     * 灵元卸载时驱逐隔离线程池，防止线程泄漏与 ClassLoader 残留
     */
    public void evict(String lingId) {
        ExecutorService executor = executors.remove(lingId);
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            log.debug("[Isolation:{}] Evicted and terminated isolated thread pool", lingId);
        }
    }
}
