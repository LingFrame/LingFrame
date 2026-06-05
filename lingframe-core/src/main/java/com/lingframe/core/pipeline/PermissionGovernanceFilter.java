package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.api.security.AuditMetadataKeys;
import com.lingframe.api.security.PermissionAuditRecord;
import com.lingframe.api.security.PermissionAuditResult;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 权限检查与审计过滤器。
 */
@Slf4j
@RequiredArgsConstructor
public class PermissionGovernanceFilter implements LingInvocationFilter {

    private final PermissionService permissionService;

    @Override
    public int getOrder() {
        return FilterPhase.GOVERNANCE + 50;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        String callerLingId = ctx.getCallerLingId();
        String capability = ctx.getRequiredPermission();
        long startNanos = System.nanoTime();

        if (capability == null || capability.isEmpty()) {
            log.debug("[Security] No required permission declared, allowing: caller={}, service={}",
                    callerLingId, ctx.getServiceFQSID());
            try {
                Object result = chain.doFilter(ctx);
                auditIfNeeded(ctx, PermissionAuditResult.ALLOWED, null, startNanos);
                return result;
            } catch (Throwable throwable) {
                auditIfNeeded(ctx, PermissionAuditResult.FAILED, describeFailure(throwable), startNanos);
                throw throwable;
            }
        }

        boolean allowed = permissionService.isAllowed(callerLingId, capability, ctx.getAccessType());
        if (!allowed) {
            log.warn("[Security] Permission denied: caller={}, capability={}, type={}",
                    callerLingId, capability, ctx.getAccessType());
            auditIfNeeded(ctx, PermissionAuditResult.DENIED, "Permission denied", startNanos);
            throw new LingInvocationException(ctx.getServiceFQSID(),
                    LingInvocationException.ErrorKind.SECURITY_REJECTED);
        }

        try {
            Object result = chain.doFilter(ctx);
            auditIfNeeded(ctx, PermissionAuditResult.ALLOWED, null, startNanos);
            return result;
        } catch (Throwable throwable) {
            auditIfNeeded(ctx, PermissionAuditResult.FAILED, describeFailure(throwable), startNanos);
            throw throwable;
        }
    }

    private void auditIfNeeded(InvocationContext ctx,
            PermissionAuditResult result,
            String failureReason,
            long startNanos) {
        if (!ctx.isShouldAudit()) {
            return;
        }

        permissionService.audit(PermissionAuditRecord.builder()
                .callerLingId(ctx.getCallerLingId())
                .principal(resolvePrincipal(ctx))
                .capability(ctx.getRequiredPermission())
                .action(ctx.getAuditAction())
                .resource(ctx.getResourceId())
                .result(result)
                .failureReason(failureReason)
                .costNanos(System.nanoTime() - startNanos)
                .build());
    }

    private String resolvePrincipal(InvocationContext ctx) {
        Map<String, Object> metadata = ctx.getMetadata();
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(AuditMetadataKeys.PRINCIPAL);
        if (value == null) {
            return null;
        }
        String principal = value.toString().trim();
        return principal.isEmpty() ? null : principal;
    }

    private String describeFailure(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isEmpty()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + ": " + message;
    }
}
