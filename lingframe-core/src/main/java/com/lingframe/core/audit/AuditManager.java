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
 */
@Slf4j
public class AuditManager {

    private static final AtomicLong DISCARD_COUNT = new AtomicLong(0);
    private static final ClassLoader CORE_CLASSLOADER = AuditManager.class.getClassLoader();

    private static final ExecutorService AUDIT_EXECUTOR = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(1000),
            NamedThreadFactory.daemon("lingframe-audit-logger", CORE_CLASSLOADER),
            (runnable, executor) -> {
                long count = DISCARD_COUNT.incrementAndGet();
                if (count == 1 || count % 100 == 0) {
                    log.warn("Audit log queue full, discarded {} records so far", count);
                }
            });

    static {
        Thread hook = new Thread(() -> {
            log.info("Shutting down Audit Executor...");
            AUDIT_EXECUTOR.shutdown();
            try {
                if (!AUDIT_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Audit executor did not terminate gracefully, forcing...");
                    AUDIT_EXECUTOR.shutdownNow();
                }
            } catch (InterruptedException e) {
                AUDIT_EXECUTOR.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("Audit Executor shutdown complete.");
        }, "audit-shutdown-hook");
        hook.setContextClassLoader(CORE_CLASSLOADER);
        Runtime.getRuntime().addShutdownHook(hook);
    }

    private AuditManager() {
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
            }, AUDIT_EXECUTOR);
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
