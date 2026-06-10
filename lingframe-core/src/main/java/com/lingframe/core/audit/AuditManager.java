package com.lingframe.core.audit;

import com.lingframe.api.security.PermissionAuditResult;
import com.lingframe.core.util.NamedThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行时治理事件使用的异步审计落点。
 * <p>
 * 生命周期管理：
 * <ul>
 *   <li>默认使用静态单例，兼容非 Spring 场景</li>
 *   <li>Spring 场景下通过 {@link #shutdown()} 由容器销毁回调触发优雅关闭</li>
 *   <li>关闭后新审计记录静默丢弃，不污染主调用链路</li>
 * </ul>
 * <p>
 * 溢出策略：
 * <ul>
 *   <li>{@link OverflowPolicy#DISCARD}（默认）：队列满时丢弃并计数</li>
 *   <li>{@link OverflowPolicy#BLOCK}：队列满时阻塞调用线程直到有空间</li>
 * </ul>
 */
@Slf4j
public class AuditManager {

    /**
     * 审计队列溢出策略
     */
    public enum OverflowPolicy {
        /** 队列满时丢弃并计数 */
        DISCARD,
        /** 队列满时阻塞调用线程直到有空间 */
        BLOCK
    }

    private static final int DEFAULT_QUEUE_CAPACITY = 1000;

    private static volatile OverflowPolicy overflowPolicy = OverflowPolicy.DISCARD;
    private static volatile int queueCapacity = DEFAULT_QUEUE_CAPACITY;

    private static final AtomicLong DISCARD_COUNT = new AtomicLong(0);
    private static final ClassLoader CORE_CLASSLOADER = AuditManager.class.getClassLoader();

    private static volatile ExecutorService AUDIT_EXECUTOR = createExecutor();

    private static volatile boolean shutdown = false;

    static {
        Thread hook = new Thread(() -> shutdownExecutor("shutdown-hook"), "audit-shutdown-hook");
        hook.setContextClassLoader(CORE_CLASSLOADER);
        Runtime.getRuntime().addShutdownHook(hook);
    }

    private AuditManager() {
    }

    /**
     * 优雅关闭审计线程池。
     * <p>
     * Spring 容器销毁时调用，确保审计记录在容器关闭前落盘。
     * 关闭后新审计记录静默丢弃。
     */
    public static void shutdown() {
        shutdownExecutor("explicit-shutdown");
    }

    /**
     * 获取已丢弃的审计记录数
     */
    public static long getDiscardCount() {
        return DISCARD_COUNT.get();
    }

    /**
     * 审计线程池是否已关闭
     */
    public static boolean isShutdown() {
        return shutdown;
    }

    /**
     * 获取当前溢出策略
     */
    public static OverflowPolicy getOverflowPolicy() {
        return overflowPolicy;
    }

    /**
     * 配置审计管理器参数。
     * <p>
     * 必须在首次审计记录之前调用，否则需先 {@link #shutdown()} 再 {@link #resetForTesting()}。
     *
     * @param policy 溢出策略，null 则保持默认 DISCARD
     * @param capacity 队列容量，小于等于 0 则保持默认 1000
     */
    public static synchronized void configure(OverflowPolicy policy, Integer capacity) {
        if (policy != null) {
            overflowPolicy = policy;
        }
        if (capacity != null && capacity > 0) {
            queueCapacity = capacity;
        }
        // 如果线程池已创建且未关闭，需要重建以应用新策略
        if (AUDIT_EXECUTOR != null && !shutdown) {
            log.warn("AuditManager.configure() called after executor initialized. " +
                    "Call shutdown() + resetForTesting() to apply changes.");
        }
    }

    private static ExecutorService createExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                NamedThreadFactory.daemon("lingframe-audit-logger", CORE_CLASSLOADER),
                new AuditOverflowHandler());
    }

    /**
     * 审计队列溢出处理器
     */
    private static class AuditOverflowHandler implements java.util.concurrent.RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (overflowPolicy == OverflowPolicy.BLOCK) {
                try {
                    executor.getQueue().put(r);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    long count = DISCARD_COUNT.incrementAndGet();
                    log.warn("Interrupted while blocking on audit queue, discarded {} records so far", count);
                }
            } else {
                long count = DISCARD_COUNT.incrementAndGet();
                if (count == 1 || count % 100 == 0) {
                    log.warn("Audit log queue full, discarded {} records so far", count);
                }
            }
        }
    }

    /**
     * 重置审计管理器状态，仅供测试使用。
     * <p>
     * 生产环境不应调用此方法。测试 teardown 中使用，确保下一个测试用例
     * 能正常记录审计。
     */
    static void resetForTesting() {
        shutdown = false;
        DISCARD_COUNT.set(0);
        AUDIT_EXECUTOR = createExecutor();
    }

    private static synchronized void shutdownExecutor(String source) {
        if (shutdown) {
            return;
        }
        shutdown = true;
        log.info("Shutting down Audit Executor (trigger={})...", source);
        ExecutorService executor = AUDIT_EXECUTOR;
        AUDIT_EXECUTOR = null;
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Audit executor did not terminate gracefully, forcing...");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Audit Executor shutdown complete (trigger={}).", source);
    }

    public static void asyncRecord(String traceId,
            String callerLingId,
            String principal,
            PermissionAuditResult result,
            String capability,
            String action,
            String resource,
            String failureReason,
            long costNanos) {
        ExecutorService executor = AUDIT_EXECUTOR;
        if (executor == null || shutdown) {
            return;
        }
        try {
            CompletableFuture.runAsync(() -> {
                ClassLoader previous = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(CORE_CLASSLOADER);
                try {
                    log.info(
                            "[AUDIT] TraceId={}, Caller={}, Principal={}, Result={}, Capability={}, Action={}, Resource={}, Cost={}ms, Failure={}",
                            traceId,
                            truncate(callerLingId, 64),
                            truncate(principal, 64),
                            result,
                            truncate(capability, 128),
                            truncate(action, 128),
                            truncate(resource, 160),
                            String.format("%.3f", costNanos / 1_000_000.0),
                            truncate(failureReason, 160));
                } catch (Exception e) {
                    log.warn("Audit log failed", e);
                } finally {
                    Thread.currentThread().setContextClassLoader(previous);
                }
            }, executor);
        } catch (Exception ignored) {
            // 忽略审计链路异常，避免污染主调用链路。
        }
    }

    public static void asyncRecord(String traceId,
            String callerLingId,
            String action,
            String resource,
            Object[] args,
            Object result,
            long cost) {
        asyncRecord(traceId,
                callerLingId,
                null,
                result == null ? PermissionAuditResult.DENIED : PermissionAuditResult.ALLOWED,
                resource,
                action,
                resource,
                null,
                cost);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
