package com.lingframe.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.ling.LingRepository;
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
    @DisplayName("更新资源权限时应持久化 patch 并同步运行时权限")
    void shouldPersistPatchAndSyncRuntimePermissionsWhenUpdatingPermissions() {
        LingRepository lingRepository = mock(LingRepository.class);
        LocalGovernanceRegistry governanceRegistry = mock(LocalGovernanceRegistry.class);
        PermissionService permissionService = mock(PermissionService.class);
        DashboardGovernanceSupport support =
                new DashboardGovernanceSupport(lingRepository, governanceRegistry, permissionService, SHARED_OBJECT_MAPPER);

        AtomicReference<GovernancePolicy> storedPatch = new AtomicReference<>();
        when(governanceRegistry.getPatch("ling1")).thenAnswer(invocation -> storedPatch.get());
        doAnswer(invocation -> {
            storedPatch.set(((GovernancePolicy) invocation.getArgument(1)).copy());
            return null;
        }).when(governanceRegistry).updatePatch(eq("ling1"), any(GovernancePolicy.class));

        ResourcePermissionDTO dto = new ResourcePermissionDTO();
        dto.setDbRead(true);
        dto.setDbWrite(false);
        dto.setCacheRead(true);
        dto.setCacheWrite(true);
        dto.setIpcServices(Arrays.asList("lingA", "lingB"));

        support.updatePermissions("ling1", dto);

        assertNotNull(storedPatch.get());
        assertEquals(5, storedPatch.get().getCapabilities().size());
        verify(permissionService).removeLing("ling1");
        verify(permissionService).grant("ling1", Capabilities.STORAGE_SQL, AccessType.READ);
        verify(permissionService).grant("ling1", Capabilities.CACHE_LOCAL, AccessType.WRITE);
        verify(permissionService).grant("ling1", Capabilities.Ling_ENABLE, AccessType.EXECUTE);
        verify(permissionService).grant("ling1", "ipc:lingA", AccessType.EXECUTE);
        verify(permissionService).grant("ling1", "ipc:lingB", AccessType.EXECUTE);
    }

    @Test
    @DisplayName("更新调用治理时应保留已有 capabilities")
    void shouldKeepCapabilitiesWhenUpdatingInvocationGovernance() {
        LingRepository lingRepository = mock(LingRepository.class);
        LocalGovernanceRegistry governanceRegistry = mock(LocalGovernanceRegistry.class);
        PermissionService permissionService = mock(PermissionService.class);
        DashboardGovernanceSupport support =
                new DashboardGovernanceSupport(lingRepository, governanceRegistry, permissionService, SHARED_OBJECT_MAPPER);

        GovernancePolicy existingPatch = new GovernancePolicy();
        existingPatch.setCapabilities(Arrays.asList(
                GovernancePolicy.CapabilityRule.builder()
                        .capability(Capabilities.CACHE_LOCAL)
                        .accessType(AccessType.WRITE.name())
                        .build()));

        AtomicReference<GovernancePolicy> storedPatch = new AtomicReference<>(existingPatch);
        when(governanceRegistry.getPatch("ling1")).thenAnswer(invocation -> storedPatch.get());
        doAnswer(invocation -> {
            storedPatch.set(((GovernancePolicy) invocation.getArgument(1)).copy());
            return null;
        }).when(governanceRegistry).updatePatch(eq("ling1"), any(GovernancePolicy.class));

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

        assertNotNull(storedPatch.get());
        assertEquals(1, storedPatch.get().getCapabilities().size());
        assertEquals(Integer.valueOf(1200), result.getTimeoutMs());
        assertEquals(Integer.valueOf(9), result.getRateLimitPerSecond());
        verify(permissionService).removeLing("ling1");
        verify(permissionService).grant("ling1", Capabilities.CACHE_LOCAL, AccessType.WRITE);
    }
}
