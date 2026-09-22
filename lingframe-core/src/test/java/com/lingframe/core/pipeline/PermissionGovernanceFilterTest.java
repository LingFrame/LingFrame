package com.lingframe.core.pipeline;

import com.lingframe.api.constant.LingCoreConstants;
import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.AuditMetadataKeys;
import com.lingframe.api.security.PermissionAuditRecord;
import com.lingframe.api.security.PermissionAuditResult;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.config.LingFrameInfo;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.security.DefaultPermissionService;
import com.lingframe.core.spi.LingFilterChain;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
        PermissionGovernanceFilter filter = new PermissionGovernanceFilter(permissionService, mockLingFrameInfo(false));

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
    @DisplayName("dev 模式下无权限声明时应放行并记录允许审计")
    void allowsWhenNoCapabilityDeclaredInDevMode() throws Throwable {
        PermissionService permissionService = mock(PermissionService.class);
        PermissionGovernanceFilter filter = new PermissionGovernanceFilter(permissionService, mockLingFrameInfo(true));

        InvocationContext ctx = createContext();
        ctx.governance().setRequiredPermission(null);

        Object result = filter.doFilter(ctx, current -> "ok");
        assertEquals("ok", result);

        PermissionAuditRecord record = captureAudit(permissionService);
        assertEquals(PermissionAuditResult.ALLOWED, record.getResult());

        ctx.recycle();
    }

    @Test
    @DisplayName("prod 模式下无权限声明时应拒绝（Deny-by-Default 零信任红线）")
    void deniesWhenNoCapabilityDeclaredInProdMode() throws Throwable {
        // prod 模式 = mockLingFrameInfo(false)
        PermissionService permissionService = mock(PermissionService.class);
        PermissionGovernanceFilter filter = new PermissionGovernanceFilter(permissionService, mockLingFrameInfo(false));

        InvocationContext ctx = createContext();
        ctx.governance().setRequiredPermission(null);

        LingFilterChain chain = current -> null;
        LingInvocationException ex = assertThrows(LingInvocationException.class,
                () -> filter.doFilter(ctx, chain));
        assertEquals(LingInvocationException.ErrorKind.SECURITY_REJECTED, ex.getKind());

        PermissionAuditRecord record = captureAudit(permissionService);
        assertEquals(PermissionAuditResult.DENIED, record.getResult());
        assertTrue(record.getFailureReason().contains("Deny-by-Default"));

        ctx.recycle();
    }

    @Test
    @DisplayName("调用成功时应记录允许审计")
    void recordsAllowedAuditOnSuccess() throws Throwable {
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.isAllowed("ling1", "perm:write", AccessType.EXECUTE)).thenReturn(true);
        PermissionGovernanceFilter filter = new PermissionGovernanceFilter(permissionService, mockLingFrameInfo(false));

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
        PermissionGovernanceFilter filter = new PermissionGovernanceFilter(permissionService, mockLingFrameInfo(false));

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

    @Test
    @DisplayName("灵核身份 caller 应豁免灵元权限表校验直接放行，并记录允许审计")
    void lingcoreCallerBypassesPermissionTable() throws Throwable {
        PermissionService permissionService = mock(PermissionService.class);
        PermissionGovernanceFilter filter = new PermissionGovernanceFilter(permissionService, mockLingFrameInfo(false));

        InvocationContext ctx = InvocationContext.obtain();
        ctx.setServiceFQSID("ling1:svc");
        ctx.setCallerLingId(LingCoreConstants.LINGCORE_LING_ID);
        ctx.governance().setRequiredPermission("perm:write");
        ctx.governance().setAccessType(AccessType.EXECUTE);
        ctx.governance().setShouldAudit(true);
        ctx.setResourceId("res");
        ctx.governance().setAuditAction("act");
        ctx.setMetadata(new HashMap<>());

        Object result = filter.doFilter(ctx, current -> "ok");
        assertEquals("ok", result);

        // 灵核豁免走 isAllowed 后命中 gate 放行（gate 挪到 Deny-by-Default 厄后必经 isAllowed 调用）
        verify(permissionService).isAllowed(
                LingCoreConstants.LINGCORE_LING_ID, "perm:write", AccessType.EXECUTE);
        // 但应记录允许审计（灵核审计边界守护）
        PermissionAuditRecord record = captureAudit(permissionService);
        assertEquals(PermissionAuditResult.ALLOWED, record.getResult());

        ctx.recycle();
    }

    @Test
    @DisplayName("灵核身份 caller 执行抛错时应记录失败审计（灵核豁免不绕过审计）")
    void lingcoreCallerRecordsFailedAuditOnExecutionError() throws Throwable {
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.isAllowed(
                LingCoreConstants.LINGCORE_LING_ID, "perm:write", AccessType.EXECUTE)).thenReturn(true);
        PermissionGovernanceFilter filter = new PermissionGovernanceFilter(permissionService, mockLingFrameInfo(false));

        InvocationContext ctx = InvocationContext.obtain();
        ctx.setServiceFQSID("ling1:svc");
        ctx.setCallerLingId(LingCoreConstants.LINGCORE_LING_ID);
        ctx.governance().setRequiredPermission("perm:write");
        ctx.governance().setAccessType(AccessType.EXECUTE);
        ctx.governance().setShouldAudit(true);
        ctx.setResourceId("res");
        ctx.governance().setAuditAction("act");
        ctx.setMetadata(new HashMap<>());

        LingFilterChain chain = current -> { throw new IllegalStateException("boom"); };

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> filter.doFilter(ctx, chain));
        assertEquals("boom", ex.getMessage());

        verify(permissionService).isAllowed(
                LingCoreConstants.LINGCORE_LING_ID, "perm:write", AccessType.EXECUTE);
        PermissionAuditRecord record = captureAudit(permissionService);
        assertEquals(PermissionAuditResult.FAILED, record.getResult());

        ctx.recycle();
    }

    @Test
    @DisplayName("灵核身份 caller + check-permissions=true 加固时仍应走权限表 enforce（toggle 控制这条路径）")
    void lingcoreCallerEnforcedWhenCheckPermissionsEnabled() throws Throwable {
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.isAllowed(
                LingCoreConstants.LINGCORE_LING_ID, "perm:write", AccessType.EXECUTE)).thenReturn(true);
        LingFrameInfo hardened = mock(LingFrameInfo.class);
        when(hardened.isDevMode()).thenReturn(false);
        when(hardened.isLingCoreCheckPermissions()).thenReturn(true);
        PermissionGovernanceFilter filter = new PermissionGovernanceFilter(permissionService, hardened);

        InvocationContext ctx = InvocationContext.obtain();
        ctx.setServiceFQSID("ling1:svc");
        ctx.setCallerLingId(LingCoreConstants.LINGCORE_LING_ID);
        ctx.governance().setRequiredPermission("perm:write");
        ctx.governance().setAccessType(AccessType.EXECUTE);
        ctx.governance().setShouldAudit(true);
        ctx.setResourceId("res");
        ctx.governance().setAuditAction("act");
        ctx.setMetadata(new HashMap<>());

        Object result = filter.doFilter(ctx, current -> "ok");
        assertEquals("ok", result);

        // 加固 toggle 开启时灵核 caller 也应走权限表 enforce
        verify(permissionService).isAllowed(
                LingCoreConstants.LINGCORE_LING_ID, "perm:write", AccessType.EXECUTE);
        PermissionAuditRecord record = captureAudit(permissionService);
        assertEquals(PermissionAuditResult.ALLOWED, record.getResult());

        ctx.recycle();
    }

    @Test
    @DisplayName("prod 模式灵核 caller 无声明 capability 时仍 Deny-by-Default 拒（零信任红线不分身份）")
    void lingcoreCallerDeniedByDefaultWhenNoCapabilityInProdMode() throws Throwable {
        PermissionService permissionService = mock(PermissionService.class);
        PermissionGovernanceFilter filter = new PermissionGovernanceFilter(permissionService, mockLingFrameInfo(false));

        InvocationContext ctx = InvocationContext.obtain();
        ctx.setServiceFQSID("ling1:svc");
        ctx.setCallerLingId(LingCoreConstants.LINGCORE_LING_ID);
        ctx.governance().setRequiredPermission(null);
        ctx.governance().setAccessType(AccessType.EXECUTE);
        ctx.governance().setShouldAudit(true);
        ctx.setResourceId("res");
        ctx.governance().setAuditAction("act");
        ctx.setMetadata(new HashMap<>());

        LingFilterChain chain = current -> null;
        LingInvocationException ex = assertThrows(LingInvocationException.class,
                () -> filter.doFilter(ctx, chain));
        assertEquals(LingInvocationException.ErrorKind.SECURITY_REJECTED, ex.getKind());

        PermissionAuditRecord record = captureAudit(permissionService);
        assertEquals(PermissionAuditResult.DENIED, record.getResult());
        assertTrue(record.getFailureReason().contains("Deny-by-Default"));

        ctx.recycle();
    }

    @Test
    @DisplayName("集成路径：灵核身份 caller + check-permissions=true 加固 + 真实 DefaultPermissionService 走权限表 enforce 拒")
    void lingcoreCallerEnforcedWithRealPermissionServiceWhenHardened() throws Throwable {
        // 真实 DefaultPermissionService + LingFrameConfig（lingCoreCheckPermissions=true 加固）
        // 加固 toggle 开启时：DefaultPermissionService:54 不豁免灵核 → isAllowed 返回 false；
        // PermissionGovernanceFilter 灵核豁免 gate !isLingCoreCheckPermissions() 也不命中 → 走 !allowed 拒路径。
        EventBus eventBus = new EventBus();
        AtomicReference<MonitoringEvents.AuditLogEvent> captured =
                new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        eventBus.subscribe("test-audit", MonitoringEvents.AuditLogEvent.class, event -> {
            captured.set(event);
            latch.countDown();
        });
        LingFrameConfig hardenedConfig = LingFrameConfig.builder()
                .devMode(false)
                .lingCoreCheckPermissions(true)
                .build();
        DefaultPermissionService realService = new DefaultPermissionService(eventBus, hardenedConfig);
        PermissionGovernanceFilter filter = new PermissionGovernanceFilter(realService, hardenedConfig);

        InvocationContext ctx = InvocationContext.obtain();
        ctx.setServiceFQSID("ling1:svc");
        ctx.setCallerLingId(LingCoreConstants.LINGCORE_LING_ID);
        ctx.governance().setRequiredPermission("lingcore:bean:read");
        ctx.governance().setAccessType(AccessType.READ);
        ctx.governance().setShouldAudit(true);
        ctx.setResourceId("res");
        ctx.governance().setAuditAction("act");
        ctx.setMetadata(new HashMap<>());

        // 加固 toggle 开启时灵核 caller 也走权限表 enforce——未声明权限应拒 SECURITY_REJECTED
        LingInvocationException ex = assertThrows(LingInvocationException.class,
                () -> filter.doFilter(ctx, current -> "ok"));
        assertEquals(LingInvocationException.ErrorKind.SECURITY_REJECTED, ex.getKind());

        assertTrue(latch.await(2, java.util.concurrent.TimeUnit.SECONDS), "审计事件未到达");
        MonitoringEvents.AuditLogEvent audit = captured.get();
        assertNotNull(audit);
        assertEquals(PermissionAuditResult.DENIED, audit.getResult());

        ctx.recycle();
    }

    @Test
    @DisplayName("集成路径：灵核身份 caller + check-permissions=false（默认）+ 真实 DefaultPermissionService 永放行不报 unauthorized")
    void lingcoreCallerBypassesWithRealPermissionServiceWhenDefaultConfig() throws Throwable {
        // 默认配置：lingCoreCheckPermissions=false，灵核身份豁免灵元权限表
        EventBus eventBus = new EventBus();
        AtomicReference<MonitoringEvents.AuditLogEvent> captured =
                new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        eventBus.subscribe("test-audit", MonitoringEvents.AuditLogEvent.class, event -> {
            captured.set(event);
            latch.countDown();
        });
        LingFrameConfig defaultConfig = LingFrameConfig.builder()
                .devMode(false)
                .lingCoreCheckPermissions(false)
                .build();
        DefaultPermissionService realService = new DefaultPermissionService(eventBus, defaultConfig);
        PermissionGovernanceFilter filter = new PermissionGovernanceFilter(realService, defaultConfig);

        InvocationContext ctx = InvocationContext.obtain();
        ctx.setServiceFQSID("ling1:svc");
        ctx.setCallerLingId(LingCoreConstants.LINGCORE_LING_ID);
        ctx.governance().setRequiredPermission("lingcore:bean:read");
        ctx.governance().setAccessType(AccessType.READ);
        ctx.governance().setShouldAudit(true);
        ctx.setResourceId("res");
        ctx.governance().setAuditAction("act");
        ctx.setMetadata(new HashMap<>());

        Object result = filter.doFilter(ctx, current -> "ok");
        assertEquals("ok", result);

        assertTrue(latch.await(2, java.util.concurrent.TimeUnit.SECONDS), "审计事件未到达");
        MonitoringEvents.AuditLogEvent audit = captured.get();
        assertNotNull(audit);
        assertEquals(PermissionAuditResult.ALLOWED, audit.getResult());

        ctx.recycle();
    }

    /** 构造 mock LingFrameInfo，指定 dev/prod 模式 */
    private LingFrameInfo mockLingFrameInfo(boolean devMode) {
        LingFrameInfo info = mock(LingFrameInfo.class);
        when(info.isDevMode()).thenReturn(devMode);
        return info;
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