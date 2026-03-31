package com.lingframe.dashboard.service;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.router.CanaryRouter;
import com.lingframe.dashboard.converter.LingInfoConverter;
import com.lingframe.dashboard.dto.InvocationGovernanceDTO;
import com.lingframe.dashboard.dto.ResourcePermissionDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardService 测试")
class DashboardServiceTest {

    @Mock
    LingFrameConfig lingFrameConfig;

    @Mock
    LingLifecycleEngine lifecycleEngine;
    @Mock
    LingRepository lingRepository;
    @Mock
    LocalGovernanceRegistry governanceRegistry;
    @Mock
    CanaryRouter canaryRouter;
    @Mock
    LingInfoConverter lingInfoConverter;
    @Mock
    PermissionService permissionService;
    @Mock
    RuntimeCoordinator runtimeCoordinator;

    @Nested
    @DisplayName("治理策略更新")
    class GovernancePolicyUpdateTests {

        @Test
        @DisplayName("更新治理策略时应同步刷新运行时权限")
        void updateGovernancePolicyRefreshesPermissionsFromPolicy() {
            DashboardService service = new DashboardService(lingFrameConfig, lifecycleEngine, lingRepository,
                    governanceRegistry, canaryRouter, lingInfoConverter, permissionService, runtimeCoordinator);

            AtomicReference<GovernancePolicy> storedPatch = new AtomicReference<>();
            when(governanceRegistry.getPatch("ling1")).thenAnswer(invocation -> storedPatch.get());
            doAnswer(invocation -> {
                storedPatch.set(((GovernancePolicy) invocation.getArgument(1)).copy());
                return null;
            }).when(governanceRegistry).updatePatch(eq("ling1"), any(GovernancePolicy.class));

            GovernancePolicy policy = new GovernancePolicy();
            policy.setCapabilities(Arrays.asList(
                    GovernancePolicy.CapabilityRule.builder()
                            .capability(Capabilities.STORAGE_SQL)
                            .accessType(AccessType.WRITE.name())
                            .build(),
                    GovernancePolicy.CapabilityRule.builder()
                            .capability(Capabilities.CACHE_LOCAL)
                            .accessType(AccessType.READ.name())
                            .build()));

            service.updateGovernancePolicy("ling1", policy);

            assertNotNull(storedPatch.get());
            verify(permissionService).removeLing("ling1");
            verify(permissionService).grant("ling1", Capabilities.STORAGE_SQL, AccessType.WRITE);
            verify(permissionService).grant("ling1", Capabilities.CACHE_LOCAL, AccessType.READ);
        }

        @Test
        @DisplayName("仅更新调用治理时应保留已有能力规则")
        void updateGovernancePolicyShouldMergeInvocationPatchWithoutClearingCapabilities() {
            DashboardService service = new DashboardService(lingFrameConfig, lifecycleEngine, lingRepository,
                    governanceRegistry, canaryRouter, lingInfoConverter, permissionService, runtimeCoordinator);

            GovernancePolicy existingPatch = new GovernancePolicy();
            existingPatch.setCapabilities(Arrays.asList(
                    GovernancePolicy.CapabilityRule.builder()
                            .capability(Capabilities.STORAGE_SQL)
                            .accessType(AccessType.READ.name())
                            .build()));

            AtomicReference<GovernancePolicy> storedPatch = new AtomicReference<>(existingPatch);
            when(governanceRegistry.getPatch("ling1")).thenAnswer(invocation -> storedPatch.get());
            doAnswer(invocation -> {
                storedPatch.set(((GovernancePolicy) invocation.getArgument(1)).copy());
                return null;
            }).when(governanceRegistry).updatePatch(eq("ling1"), any(GovernancePolicy.class));

            GovernancePolicy invocationOnlyPolicy = new GovernancePolicy();
            invocationOnlyPolicy.getInvocation().setTimeoutMs(1800);
            invocationOnlyPolicy.getInvocation().setRateLimitPerSecond(6);

            service.updateGovernancePolicy("ling1", invocationOnlyPolicy);

            GovernancePolicy saved = storedPatch.get();
            assertNotNull(saved);
            assertEquals(1, saved.getCapabilities().size());
            assertEquals(Integer.valueOf(1800), saved.getInvocation().getTimeoutMs());
            assertEquals(Integer.valueOf(6), saved.getInvocation().getRateLimitPerSecond());

            verify(permissionService).removeLing("ling1");
            verify(permissionService).grant("ling1", Capabilities.STORAGE_SQL, AccessType.READ);
        }
    }

    @Nested
    @DisplayName("权限更新")
    class PermissionUpdateTests {

        @Test
        @DisplayName("更新权限时应持久化策略并同步运行时权限")
        void updatePermissionsPersistsPolicyAndSyncsRuntimePermissions() {
            DashboardService service = new DashboardService(lingFrameConfig, lifecycleEngine, lingRepository,
                    governanceRegistry, canaryRouter, lingInfoConverter, permissionService, runtimeCoordinator);

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

            service.updatePermissions("ling1", dto);

            GovernancePolicy saved = storedPatch.get();
            assertEquals(5, saved.getCapabilities().size());

            verify(permissionService).removeLing("ling1");
            verify(permissionService).grant("ling1", Capabilities.STORAGE_SQL, AccessType.READ);
            verify(permissionService).grant("ling1", Capabilities.CACHE_LOCAL, AccessType.WRITE);
            verify(permissionService).grant("ling1", Capabilities.Ling_ENABLE, AccessType.EXECUTE);
            verify(permissionService).grant("ling1", "ipc:lingA", AccessType.EXECUTE);
            verify(permissionService).grant("ling1", "ipc:lingB", AccessType.EXECUTE);
        }
    }

    @Nested
    @DisplayName("调用治理更新")
    class InvocationGovernanceUpdateTests {

        @Test
        @DisplayName("更新调用治理时应保留资源权限并返回最新调用治理配置")
        void updateInvocationGovernanceShouldKeepCapabilitiesAndReturnUpdatedView() {
            DashboardService service = new DashboardService(lingFrameConfig, lifecycleEngine, lingRepository,
                    governanceRegistry, canaryRouter, lingInfoConverter, permissionService, runtimeCoordinator);

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
                    .build();

            InvocationGovernanceDTO result = service.updateInvocationGovernance("ling1", dto);

            GovernancePolicy saved = storedPatch.get();
            assertEquals(1, saved.getCapabilities().size());
            assertEquals(Integer.valueOf(1200), saved.getInvocation().getTimeoutMs());
            assertEquals(Integer.valueOf(9), result.getRateLimitPerSecond());
            assertEquals(Integer.valueOf(4), result.getMaxConcurrentThreads());

            verify(permissionService).removeLing("ling1");
            verify(permissionService).grant("ling1", Capabilities.CACHE_LOCAL, AccessType.WRITE);
        }
    }
}
