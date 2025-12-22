package com.lingframe.core.kernel;

import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.audit.AuditManager;
import com.lingframe.core.monitor.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * 治理内核：统一执行逻辑
 */
@Slf4j
@RequiredArgsConstructor
public class GovernanceKernel {

    private final PermissionService permissionService;

    public Object invoke(InvocationContext ctx, Supplier<Object> executor) {
        // 1. Trace 开启
        boolean isRootTrace = (TraceContext.get() == null);

        // 修正：使用新增的 setTraceId 方法
        if (ctx.getTraceId() != null) {
            TraceContext.setTraceId(ctx.getTraceId());
        } else if (isRootTrace) {
            TraceContext.start();
        }

        // 回填 Context，确保后续 Audit 能拿到最终的 ID
        ctx.setTraceId(TraceContext.get());

        long startTime = System.nanoTime();
        boolean success = false;
        Object result = null;
        Throwable error = null;

        try {
            // 2. Auth 鉴权
            // 2.1 检查插件级权限
            if (!permissionService.isAllowed(ctx.getPluginId(), "PLUGIN_ENABLE", AccessType.EXECUTE)) {
                throw new SecurityException("Plugin is disabled: " + ctx.getPluginId());
            }

            // 2.2 核心检查：检查推导出的权限
            // 如果 Adapter 没推导出权限，则默认检查 resourceId
            String perm = ctx.getRequiredPermission();
            if (perm == null || perm.isBlank()) {
                perm = ctx.getResourceId();
            }

            // 使用上下文指定的 AccessType，默认为 EXECUTE
            AccessType type = ctx.getAccessType() != null ? ctx.getAccessType() : AccessType.EXECUTE;

            if (!permissionService.isAllowed(ctx.getPluginId(), perm, type)) {
                log.warn("⛔ Permission Denied: Plugin=[{}] needs=[{}] type=[{}]", ctx.getPluginId(), perm, type);
                throw new SecurityException("Access Denied: " + perm);
            }

            // 2.3 检查资源级权限
            if (!permissionService.isAllowed(ctx.getPluginId(), ctx.getResourceId(), AccessType.EXECUTE)) {
                throw new SecurityException("Access Denied: " + ctx.getResourceId());
            }

            // 3. Audit In
            if (log.isDebugEnabled()) {
                log.debug("Kernel Ingress: [{}] {} | Trace={}", ctx.getResourceType(), ctx.getResourceId(), ctx.getTraceId());
            }

            // 4. Execute 真实业务
            result = executor.get();
            success = true;
            return result;
        } catch (Throwable e) {
            error = e;
            throw e;// 异常抛出给上层处理
        } finally {
            long cost = System.nanoTime() - startTime;

            // 5. Audit Out (审计落盘)
            // 只有标记为 shouldAudit 的请求才记录，避免日志泛滥
            if (ctx.isShouldAudit()) {
                String action = ctx.getAuditAction();
                if (action == null) action = ctx.getOperation();

                try {
                    AuditManager.asyncRecord(
                            ctx.getTraceId(),
                            ctx.getPluginId(), // 记录谁被调用，或者记录 ctx.getCallerPluginId()
                            action,
                            ctx.getResourceId(),
                            ctx.getArgs(),
                            success ? result : error,
                            cost
                    );
                } catch (Exception e) {
                    log.error("Audit failed", e);
                }
            }

            // 6. Trace 清理
            if (isRootTrace) {
                TraceContext.clear();
            }
        }
    }
}