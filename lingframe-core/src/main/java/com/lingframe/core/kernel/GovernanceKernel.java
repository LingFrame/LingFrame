package com.lingframe.core.kernel;

import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.audit.AuditManager;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.governance.GovernanceDecision;
import com.lingframe.core.monitor.TraceContext;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.core.plugin.PluginRuntime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * 治理内核：统一执行逻辑
 */
@Slf4j
@RequiredArgsConstructor
public class GovernanceKernel {

    private final PermissionService permissionService;

    private final GovernanceArbitrator arbitrator;

    private final EventBus eventBus;

    /**
     * 核心拦截入口
     *
     * @param runtime  当前插件运行时 (Host调用时可能为null)
     * @param method   目标方法
     * @param ctx      调用上下文
     * @param executor 真实执行逻辑
     */
    public Object invoke(PluginRuntime runtime, Method method, InvocationContext ctx, Supplier<Object> executor) {
        // Trace 开启
        boolean isRootTrace = (TraceContext.get() == null);

        if (ctx.getTraceId() != null) {
            TraceContext.setTraceId(ctx.getTraceId());
        } else if (isRootTrace) {
            TraceContext.start();
        }
        // 回填 Context，确保后续 Audit 能拿到最终的 ID
        ctx.setTraceId(TraceContext.get());

        // 深度递增 & 发布 Trace Start
        TraceContext.increaseDepth();
        int currentDepth = TraceContext.getDepth();

        // 发布入站日志
        publishTrace(ctx.getTraceId(), ctx.getPluginId(),
                String.format("→ INGRESS: %s calls %s", ctx.getCallerPluginId(), ctx.getResourceId()),
                "IN", currentDepth);

        long startTime = System.nanoTime();
        boolean success = false;
        Object result = null;
        Throwable error = null;

        // 治理仲裁 (获取上帝视角)
        GovernanceDecision decision = arbitrator.arbitrate(runtime, method, ctx);
        enrichContext(ctx, decision);

        try {
            // Auth 鉴权
            // 检查插件级权限
            // 这一步必须查 Target，因为如果 Target 挂了，谁调都没用
            if (!permissionService.isAllowed(ctx.getPluginId(), Capabilities.PLUGIN_ENABLE, AccessType.EXECUTE)) {
                throw new PermissionDeniedException(ctx.getPluginId(), Capabilities.PLUGIN_ENABLE);
            }

            // 核心检查：检查推导出的权限(始终检查 Caller)
            // 🔥无论是 Web 还是 RPC，永远检查 Caller
            // Web 请求的 Caller 是 "host-gateway"
            // RPC 请求的 Caller 是 "order-plugin"
            String callerId = ctx.getCallerPluginId();
            if (callerId == null) {
                callerId = ctx.getPluginId();
            }

            // 如果 Adapter 没推导出权限，则默认检查 resourceId
            String perm = ctx.getRequiredPermission();
            if (perm == null || perm.trim().isEmpty()) {
                perm = ctx.getResourceId();
            }

            // 使用上下文指定的 AccessType，默认为 EXECUTE
            AccessType type = ctx.getAccessType() != null ? ctx.getAccessType() : AccessType.EXECUTE;

            if (!permissionService.isAllowed(callerId, perm, type)) {
                String source = (decision != null && decision.getSource() != null) ? decision.getSource() : "Unknown";
                log.warn("⛔ Permission Denied: Plugin=[{}] needs=[{}] type=[{}] (Rule Source: {})",
                        callerId, perm, type, source);
                throw new PermissionDeniedException(callerId, perm, type);
            }

            // Audit In
            if (log.isDebugEnabled()) {
                log.debug("Kernel Ingress: [{}] {} | Trace={}", ctx.getResourceType(), ctx.getResourceId(),
                        ctx.getTraceId());
            }

            // Execute 真实业务 (支持重试)
            int retryCount = (decision != null && decision.getRetryCount() != null) ? decision.getRetryCount() : 0;
            int attempts = 0;

            while (true) {
                try {
                    attempts++;
                    result = executor.get();
                    success = true;

                    // 发布 Trace Success
                    publishTrace(ctx.getTraceId(), ctx.getPluginId(),
                            "← RETURN: Success", "OUT", currentDepth);

                    return result;
                } catch (Throwable e) {
                    error = e;
                    // 如果还有重试机会，且不是权限类严重错误
                    if (attempts <= retryCount && !(e instanceof PermissionDeniedException)) {
                        log.warn("[{}] Execution failed, retrying ({}/{}). Error: {}",
                                ctx.getResourceId(), attempts, retryCount, e.getMessage());
                        continue;
                    }

                    // 重试耗尽，检查降级
                    if (decision != null && decision.getFallbackValue() != null) {
                        log.info("[{}] Fallback triggered. Returning: {}", ctx.getResourceId(),
                                decision.getFallbackValue());
                        publishTrace(ctx.getTraceId(), ctx.getPluginId(),
                                "← FALLBACK: " + decision.getFallbackValue(), "OUT", currentDepth);
                        // 降级视为业务成功，或者是特殊的"降级成功"
                        // 这里我们标记 success=false (业务失败)，或者需要一个新的状态?
                        // Audit Log 可能需要区分。
                        success = false; // 严格来说业务失败了
                        result = decision.getFallbackValue(); // 但为了避免抛出异常，我们返回结果

                        // 修正：如果降级成功，我们不想 finally 里的 audit 记录为 error，
                        // 但 error 变量已经赋值了。
                        // 让我们把 error 置空，表示被处理了。
                        error = null;
                        return result;
                        // 注意：这里 return 会去执行 finally
                    }

                    throw e; // 继续抛出
                }
            }
        } catch (Throwable e) {
            error = e;

            // 发布 Trace Error
            publishTrace(ctx.getTraceId(), ctx.getPluginId(),
                    "✖ ERROR: " + e.getMessage(), "ERROR", currentDepth);

            throw e;// 异常抛出给上层处理
        } finally {
            long cost = System.nanoTime() - startTime;

            // Audit Out (审计落盘)
            // 只有标记为 shouldAudit 的请求才记录，避免日志泛滥
            if (ctx.isShouldAudit()) {
                String action = ctx.getAuditAction();
                if (action == null)
                    action = ctx.getOperation();
                String caller = ctx.getCallerPluginId() != null ? ctx.getCallerPluginId() : ctx.getPluginId();

                try {
                    AuditManager.asyncRecord(
                            ctx.getTraceId(),
                            caller, // 记录谁被调用，或者记录 ctx.getCallerPluginId()
                            action,
                            ctx.getResourceId(),
                            ctx.getArgs(),
                            success ? result : error,
                            cost);

                    // 发布实时 Audit 事件 (供前端展示)
                    eventBus.publish(new MonitoringEvents.AuditLogEvent(
                            ctx.getTraceId(), caller, action, ctx.getResourceId(), success, cost));

                } catch (Exception e) {
                    log.error("Audit failed", e);
                }
            }

            // 深度递减 & 清理
            TraceContext.decreaseDepth();

            // Trace 清理
            if (isRootTrace) {
                TraceContext.clear();
            }
        }
    }

    private void publishTrace(String traceId, String pluginId, String action, String type, int depth) {
        if (eventBus != null) {
            try {
                eventBus.publish(new MonitoringEvents.TraceLogEvent(traceId, pluginId, action, type, depth));
            } catch (Exception e) {
                // 吞掉监控异常，不影响业务
                log.warn("Failed to publish trace event", e);
            }
        }
    }

    private void enrichContext(InvocationContext ctx, GovernanceDecision decision) {
        if (decision == null)
            return;

        // 🔥 只有 ctx 未设置时才应用 decision 的值，尊重调用方的预设
        if (decision.getRequiredPermission() != null && ctx.getRequiredPermission() == null)
            ctx.setRequiredPermission(decision.getRequiredPermission());
        if (decision.getAccessType() != null && ctx.getAccessType() == null)
            ctx.setAccessType(decision.getAccessType());
        if (decision.getAuditEnabled() != null)
            ctx.setShouldAudit(decision.getAuditEnabled());
        if (decision.getAuditAction() != null && ctx.getAuditAction() == null)
            ctx.setAuditAction(decision.getAuditAction());
        if (decision.getSource() != null)
            ctx.setRuleSource(decision.getSource());
        if (decision.getTimeout() != null && ctx.getTimeout() == null)
            ctx.setTimeout((int) decision.getTimeout().toMillis());
    }
}