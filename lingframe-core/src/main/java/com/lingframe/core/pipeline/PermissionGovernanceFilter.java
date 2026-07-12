package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.api.security.AuditMetadataKeys;
import com.lingframe.api.security.PermissionAuditRecord;
import com.lingframe.api.security.PermissionAuditResult;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameInfo;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 权限检查与审计过滤器。
 * <p>
 * prod 模式下执行零信任红线：未显式声明 requiredPermission 的调用一律拒绝，
 * 防止启发式推导（{@link com.lingframe.core.governance.GovernanceStrategy}）因方法命名不规范产生越权风险。
 * dev 模式下维持原有「未声明即放行」兜底，便于开发期快速验证。
 */
@Slf4j
@RequiredArgsConstructor
public class PermissionGovernanceFilter implements LingInvocationFilter {

    private final PermissionService permissionService;
    /**
     * 灵核全局配置只读门面，用于判断 dev/prod 模式。
     * 可为 null（native/test 未注入时），此时按 prod 模式 Deny-by-Default 处理。
     */
    private final LingFrameInfo lingFrameInfo;

    @Override
    public int getOrder() {
        return FilterPhase.GOVERNANCE + 50;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        String callerLingId = ctx.getCallerLingId();
        String capability = ctx.governance().getRequiredPermission();
        long startNanos = System.nanoTime();

        if (capability == null || capability.isEmpty()) {
            // prod 模式零信任红线：未显式声明权限的一律拒绝，防止启发式推导越权。
            // dev 模式维持「未声明即放行」兜底，便于开发期快速验证。
            boolean devMode = lingFrameInfo != null && lingFrameInfo.isDevMode();
            if (!devMode) {
                log.warn("[Security] Deny-by-Default: no required permission declared in prod mode, caller={}, service={}",
                        callerLingId, ctx.getServiceFQSID());
                auditIfNeeded(ctx, PermissionAuditResult.DENIED, "Deny-by-Default (no permission declared)", startNanos);
                throw new LingInvocationException(ctx.getServiceFQSID(),
                        LingInvocationException.ErrorKind.SECURITY_REJECTED);
            }
            log.debug("[Security] No required permission declared, allowing (dev mode): caller={}, service={}",
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

        boolean allowed = permissionService.isAllowed(callerLingId, capability, ctx.governance().getAccessType());
        if (!allowed) {
            log.warn("[Security] Permission denied: caller={}, capability={}, type={}",
                    callerLingId, capability, ctx.governance().getAccessType());
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
        if (!ctx.governance().isShouldAudit()) {
            return;
        }

        permissionService.audit(PermissionAuditRecord.builder()
                .callerLingId(ctx.getCallerLingId())
                .principal(resolvePrincipal(ctx))
                .capability(ctx.governance().getRequiredPermission())
                .action(ctx.governance().getAuditAction())
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
