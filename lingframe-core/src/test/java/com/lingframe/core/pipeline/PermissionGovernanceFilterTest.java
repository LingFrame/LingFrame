package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.spi.LingFilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("PermissionGovernanceFilter 测试")
class PermissionGovernanceFilterTest {

    @Test
    @DisplayName("缺失所需权限时应拒绝调用并记录审计")
    void rejectsWhenCapabilityMissing() throws Throwable {
        PermissionService permissionService = mock(PermissionService.class);
        PermissionGovernanceFilter filter = new PermissionGovernanceFilter(permissionService);

        InvocationContext ctx = InvocationContext.obtain();
        ctx.setServiceFQSID("ling1:svc");
        ctx.setCallerLingId("ling1");
        ctx.setAccessType(AccessType.EXECUTE);
        ctx.setRequiredPermission(null);
        ctx.setShouldAudit(true);
        ctx.setResourceId("res");
        ctx.setAuditAction("act");

        LingFilterChain chain = c -> null;
        LingInvocationException ex = assertThrows(LingInvocationException.class, () -> filter.doFilter(ctx, chain));
        assertEquals(LingInvocationException.ErrorKind.SECURITY_REJECTED, ex.getKind());
        verify(permissionService).audit("ling1", "res", "act", false);

        ctx.recycle();
    }
}
