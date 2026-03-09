package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 权限与审计治理过滤器
 * 职责：
 * 1. 执行权限校验 (Permission Check)
 * 2. 记录审计日志 (Audit Logging)
 * 统一收敛 Web 与 RPC 的治理逻辑，实现“一处定义，全链路生效”。
 */
@Slf4j
@RequiredArgsConstructor
public class PermissionGovernanceFilter implements LingInvocationFilter {

    private final PermissionService permissionService;

    @Override
    public int getOrder() {
        // 权限校验位于并发控制之后，路由选择之后，真正调用之前
        return FilterPhase.GOVERNANCE + 50;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        String callerLingId = ctx.getCallerLingId();
        String capability = ctx.getRequiredPermission();

        // 1. 权限校验
        if (capability != null && !capability.isEmpty()) {
            boolean allowed = permissionService.isAllowed(callerLingId, capability, ctx.getAccessType());
            if (!allowed) {
                log.warn("[Security] Permission denied: caller={}, capability={}, type={}",
                        callerLingId, capability, ctx.getAccessType());

                // 审计未授权尝试
                if (ctx.isShouldAudit()) {
                    permissionService.audit(callerLingId, ctx.getResourceId(), ctx.getAuditAction(), false);
                }

                throw new LingInvocationException(ctx.getServiceFQSID(),
                        LingInvocationException.ErrorKind.SECURITY_REJECTED);
            }
        }

        // 2. 执行后续链条
        Object result;
        try {
            result = chain.doFilter(ctx);

            // 3. 审计成功事件
            if (ctx.isShouldAudit()) {
                permissionService.audit(callerLingId, ctx.getResourceId(), ctx.getAuditAction(), true);
            }

            return result;
        } catch (Throwable t) {
            // 审计失败事件（如果需要记录由于执行异常导致的失败，此处可扩展）
            throw t;
        }
    }
}
