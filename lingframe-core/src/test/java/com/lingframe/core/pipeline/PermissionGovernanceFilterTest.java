package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.AuditMetadataKeys;
import com.lingframe.api.security.PermissionAuditRecord;
import com.lingframe.api.security.PermissionAuditResult;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.spi.LingFilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PermissionGovernanceFilter 测试")
class PermissionGovernanceFilterTest {

    @Test
    @DisplayName("权限校验拒绝时应拒绝调用并记录审计")
    void rejectsWhenPermissionDenied() throws Throwable {
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.isAllowed("ling1", "perm:write", AccessType.EXECUTE)).thenReturn(false);
        PermissionGovernanceFilter filter = new PermissionGovernanceFilter(permissionService);

        InvocationContext ctx = createContext();
        ctx.governance().setRequiredPermission("perm:write");

        LingFilterChain chain = current -> null;
        LingInvocationException ex = assertThrows(LingInvocationException.class, () -> filter.doFilter(ctx, chain));
        assertEquals(LingInvocationException.ErrorKind.SECURITY_REJECTED, ex.getKind());

        PermissionAuditRecord record = captureAudit(permissionService);
        assertEquals(PermissionAuditResult.DENIED, record.getResult());
        assertEquals("Permission denied", record.getFailureReason());
        assertEquals("alice", record.getPrincipal());

        ctx.recycle();
    }

    @Test
    @DisplayName("无权限声明时应放行并记录允许审计")
    void allowsWhenNoCapabilityDeclared() throws Throwable {
        PermissionService permissionService = mock(PermissionService.class);
        PermissionGovernanceFilter filter = new PermissionGovernanceFilter(permissionService);

        InvocationContext ctx = createContext();
        ctx.governance().setRequiredPermission(null);

        Object result = filter.doFilter(ctx, current -> "ok");
        assertEquals("ok", result);

        PermissionAuditRecord record = captureAudit(permissionService);
        assertEquals(PermissionAuditResult.ALLOWED, record.getResult());

        ctx.recycle();
    }

    @Test
    @DisplayName("调用成功时应记录允许审计")
    void recordsAllowedAuditOnSuccess() throws Throwable {
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.isAllowed("ling1", "perm:write", AccessType.EXECUTE)).thenReturn(true);
        PermissionGovernanceFilter filter = new PermissionGovernanceFilter(permissionService);

        InvocationContext ctx = createContext();
        ctx.governance().setRequiredPermission("perm:write");

        Object result = filter.doFilter(ctx, current -> "ok");
        assertEquals("ok", result);

        PermissionAuditRecord record = captureAudit(permissionService);
        assertEquals(PermissionAuditResult.ALLOWED, record.getResult());
        assertEquals("perm:write", record.getCapability());
        assertTrue(record.getCostNanos() > 0L);

        ctx.recycle();
    }

    @Test
    @DisplayName("调用已放行但执行抛错时应记录失败审计")
    void recordsFailedAuditWhenInvocationThrows() throws Throwable {
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.isAllowed("ling1", "perm:write", AccessType.EXECUTE)).thenReturn(true);
        PermissionGovernanceFilter filter = new PermissionGovernanceFilter(permissionService);

        InvocationContext ctx = createContext();
        ctx.governance().setRequiredPermission("perm:write");

        LingFilterChain chain = current -> {
            throw new IllegalStateException("boom");
        };

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> filter.doFilter(ctx, chain));
        assertEquals("boom", ex.getMessage());

        PermissionAuditRecord record = captureAudit(permissionService);
        assertEquals(PermissionAuditResult.FAILED, record.getResult());
        assertEquals("IllegalStateException: boom", record.getFailureReason());
        assertTrue(record.getCostNanos() > 0L);

        ctx.recycle();
    }

    private InvocationContext createContext() {
        InvocationContext ctx = InvocationContext.obtain();
        ctx.setServiceFQSID("ling1:svc");
        ctx.setCallerLingId("ling1");
        ctx.governance().setAccessType(AccessType.EXECUTE);
        ctx.governance().setShouldAudit(true);
        ctx.setResourceId("res");
        ctx.governance().setAuditAction("act");
        HashMap<String, Object> metadata = new HashMap<>();
        metadata.put(AuditMetadataKeys.PRINCIPAL, "alice");
        ctx.setMetadata(metadata);
        return ctx;
    }

    private PermissionAuditRecord captureAudit(PermissionService permissionService) {
        ArgumentCaptor<PermissionAuditRecord> captor = ArgumentCaptor.forClass(PermissionAuditRecord.class);
        verify(permissionService).audit(captor.capture());
        return captor.getValue();
    }
}
