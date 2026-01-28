package com.lingframe.core.audit;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计管理器 (异步非阻塞)
 */
@Slf4j
public class AuditManager {

    // 丢弃计数器，用于告警
    private static final AtomicLong discardCount = new AtomicLong(0);

    // 使用独立的单线程线程池，保证日志顺序，且不占用 ForkJoinPool
    private static final ExecutorService AUDIT_EXECUTOR = new ThreadPoolExecutor(
            1,
            1,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(1000), // 缓冲队列 1000
            r -> {
                Thread thread = new Thread(r, "lingframe-audit-logger");
                thread.setDaemon(true);
                thread.setUncaughtExceptionHandler((t, e) -> log.error("线程池线程 {} 异常: {}", t.getName(), e.getMessage()));
                return thread;
            },
            // 自定义拒绝策略：丢弃日志但记录告警
            (r, executor) -> {
                long count = discardCount.incrementAndGet();
                if (count == 1 || count % 100 == 0) {
                    log.warn("⚠️ Audit log queue full, discarded {} logs so far", count);
                }
            });

    // 注册 Shutdown Hook（替代无效的 @PreDestroy 静态方法）
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
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
        }, "audit-shutdown-hook"));
    }

    /**
     * 异步记录审计日志
     */
    public static void asyncRecord(String traceId, String callerPluginId, String action, String resource,
                                   Object[] args, Object result, long cost) {
        // 增加防御性 try-catch，防止提交任务本身抛出异常
        try {
            // 使用独立线程池执行，避免阻塞业务线程
            CompletableFuture.runAsync(() -> {
                try {
                    // 生产环境应写入 ES/DB，此处演示打印日志
                    // 仅记录操作是否成功，不记录具体异常堆栈（由 Monitor 负责）
                    boolean success = (result != null) || (cost > 0);

                    // 简单的 JSON 格式化，方便日志系统采集
                    String argsStr = args == null ? "[]" : "args_hash:" + Integer.toHexString(Arrays.hashCode(args));
                    String resultStr = result == null ? "null"
                            : result.toString().substring(0, Math.min(50, result.toString().length()));

                    log.info(
                            "[AUDIT] TraceId={}, Plugin={}, Action={}, Resource={}, Cost={}ms, Result={}, Args={}, Data={}",
                            traceId, callerPluginId, action, resource, String.format("%.3f", cost / 1_000_000.0),
                            success ? "Success" : "Void", argsStr, resultStr);
                } catch (Exception e) {
                    log.warn("Audit log failed", e);
                }
            }, AUDIT_EXECUTOR);
        } catch (Exception e) {
            // 这里的异常通常意味着线程池已关闭或严重错误，日志可能也打不出来，但尽力而为
            // 忽略丢弃异常
        }
    }
}