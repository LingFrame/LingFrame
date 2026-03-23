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
    @DisplayName("缺失所需权限时应拒绝调用并记录审计")
    void rejectsWhenCapabilityMissing() throws Throwable {
        PermissionService permissionService = mock(PermissionService.class);
        PermissionGovernanceFilter filter = new PermissionGovernanceFilter(permissionService);

        InvocationContext ctx = createContext();
        ctx.setRequiredPermission(null);

        LingFilterChain chain = current -> null;
        LingInvocationException ex = assertThrows(LingInvocationException.class, () -> filter.doFilter(ctx, chain));
        assertEquals(LingInvocationException.ErrorKind.SECURITY_REJECTED, ex.getKind());

        PermissionAuditRecord record = captureAudit(permissionService);
        assertEquals(PermissionAuditResult.DENIED, record.getResult());
        assertEquals("Missing required permission", record.getFailureReason());
        assertEquals("alice", record.getPrincipal());

        ctx.recycle();
    }

    @Test
    @DisplayName("调用成功时应记录允许审计")
    void recordsAllowedAuditOnSuccess() throws Throwable {
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.isAllowed("ling1", "perm:write", AccessType.EXECUTE)).thenReturn(true);
        PermissionGovernanceFilter filter = new PermissionGovernanceFilter(permissionService);

        InvocationContext ctx = createContext();
        ctx.setRequiredPermission("perm:write");

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
        ctx.setRequiredPermission("perm:write");

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
        ctx.setAccessType(AccessType.EXECUTE);
        ctx.setShouldAudit(true);
        ctx.setResourceId("res");
        ctx.setAuditAction("act");
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
