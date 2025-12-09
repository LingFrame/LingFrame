package com.lingframe.core.audit;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

/**
 * 审计管理器 (异步非阻塞)
 */
@Slf4j
public class AuditManager {

    public static void asyncRecord(String traceId, String callerPluginId, String action, String resource,
                                   Object[] args, Object result, long cost) {
        // 使用 CompletableFuture 异步执行，避免阻塞业务线程
        CompletableFuture.runAsync(() -> {
            try {
                // 生产环境应写入 ES/DB，此处演示打印日志
                // 仅记录操作是否成功，不记录具体异常堆栈（由 Monitor 负责）
                boolean success = (result != null) || (cost > 0);

                log.info("[AUDIT] TraceId={}, Plugin={}, Action={}, Resource={}, Cost={}ms, Result={}",
                        traceId, callerPluginId, action, resource, cost / 1_000_000, success ? "Success" : "Void");
            } catch (Exception e) {
                log.warn("Audit log failed", e);
            }
        });
    }
}