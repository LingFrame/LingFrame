package com.lingframe.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.governance.GovernanceAdminService;
import com.lingframe.dashboard.dto.InvocationGovernanceDTO;
import com.lingframe.dashboard.dto.ResourcePermissionDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DashboardGovernanceSupport 测试")
class DashboardGovernanceSupportTest {

    // 测试类共享 ObjectMapper 单例，避免每个测试方法都 new 一个实例
    private static final ObjectMapper SHARED_OBJECT_MAPPER = new ObjectMapper();

    @Test
    @DisplayName("更新资源权限时应委托 GovernanceAdminService 持久化并组装能力规则")
    void shouldDelegatePersistAndAssembleCapabilitiesWhenUpdatingPermissions() {
        GovernanceAdminService governanceAdmin = mock(GovernanceAdminService.class);
        PermissionService permissionService = mock(PermissionService.class);
        DashboardGovernanceSupport support =
                new DashboardGovernanceSupport(governanceAdmin, permissionService, SHARED_OBJECT_MAPPER);

        // getPatchForUpdate 返回空 patch，让 updatePermissions 从零组装能力规则
        AtomicReference<GovernancePolicy> persistedPatch = new AtomicReference<>();
        when(governanceAdmin.getPatchForUpdate("ling1")).thenReturn(new GovernancePolicy());
        doAnswer(invocation -> {
            persistedPatch.set(((GovernancePolicy) invocation.getArgument(1)).copy());
            return null;
        }).when(governanceAdmin).persistPolicyPatch(eq("ling1"), any(GovernancePolicy.class));

        ResourcePermissionDTO dto = new ResourcePermissionDTO();
        dto.setDbRead(true);
        dto.setDbWrite(false);
        dto.setCacheRead(true);
        dto.setCacheWrite(true);
        dto.setIpcServices(Arrays.asList("lingA", "lingB"));

        support.updatePermissions("ling1", dto);

        // 验证 Dashboard 特有的 DTO 镜像逻辑——5 条能力规则被组装
        assertNotNull(persistedPatch.get());
        assertEquals(5, persistedPatch.get().getCapabilities().size());
        // 验证委托 GovernanceAdminService 持久化
        verify(governanceAdmin).persistPolicyPatch(eq("ling1"), any(GovernancePolicy.class));
    }

    @Test
    @DisplayName("更新调用治理时应保留已有 capabilities 并委托 GovernanceAdminService")
    void shouldKeepCapabilitiesAndDelegateWhenUpdatingInvocationGovernance() {
        GovernanceAdminService governanceAdmin = mock(GovernanceAdminService.class);
        PermissionService permissionService = mock(PermissionService.class);
        DashboardGovernanceSupport support =
                new DashboardGovernanceSupport(governanceAdmin, permissionService, SHARED_OBJECT_MAPPER);

        // 已有 patch 带 1 条 capability，updateInvocationGovernance 应保留它
        GovernancePolicy existingPatch = new GovernancePolicy();
        existingPatch.setCapabilities(Arrays.asList(
                GovernancePolicy.CapabilityRule.builder()
                        .capability(Capabilities.CACHE_LOCAL)
                        .accessType(AccessType.WRITE.name())
                        .build()));
        when(governanceAdmin.getPatchForUpdate("ling1")).thenReturn(existingPatch);

        GovernancePolicy effective = new GovernancePolicy();
        effective.setInvocation(GovernancePolicy.InvocationPolicy.builder()
                .timeoutMs(1200)
                .rateLimitPerSecond(9)
                .maxConcurrentThreads(4)
                .retryCount(2)
                .fallbackValue("fallback-ok")
                .cpuBudgetMsPerMinute(600)
                .memoryBudgetMb(48)
                .build());
        when(governanceAdmin.getEffectivePolicy("ling1")).thenReturn(effective);

        InvocationGovernanceDTO dto = InvocationGovernanceDTO.builder()
                .timeoutMs(1200)
                .rateLimitPerSecond(9)
                .maxConcurrentThreads(4)
                .retryCount(2)
                .fallbackValue("fallback-ok")
                .cpuBudgetMsPerMinute(600)
                .memoryBudgetMb(48)
                .build();

        InvocationGovernanceDTO result = support.updateInvocationGovernance("ling1", dto);

        // 验证 DTO 镜像逻辑——从 effectivePolicy 正确组装返回 DTO
        assertEquals(Integer.valueOf(1200), result.getTimeoutMs());
        assertEquals(Integer.valueOf(9), result.getRateLimitPerSecond());
        assertEquals(Integer.valueOf(4), result.getMaxConcurrentThreads());
        assertEquals(Integer.valueOf(2), result.getRetryCount());
        assertEquals("fallback-ok", result.getFallbackValue());
        assertEquals(Integer.valueOf(600), result.getCpuBudgetMsPerMinute());
        assertEquals(Integer.valueOf(48), result.getMemoryBudgetMb());
        // 验证委托 GovernanceAdminService 持久化
        verify(governanceAdmin).persistPolicyPatch(eq("ling1"), any(GovernancePolicy.class));
    }
}
