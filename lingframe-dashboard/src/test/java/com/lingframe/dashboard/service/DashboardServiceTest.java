package com.lingframe.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingUninstallResult;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.router.CanaryRouter;
import com.lingframe.core.spi.LeakRiskLevel;
import com.lingframe.core.spi.LeakRiskReport;
import com.lingframe.dashboard.converter.LingInfoConverter;
import com.lingframe.dashboard.dto.InvocationGovernanceDTO;
import com.lingframe.dashboard.dto.LingUninstallResultDTO;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardService 测试")
class DashboardServiceTest {

    // 测试类共享 ObjectMapper 单例，避免每个测试方法都 new 一个实例
    private static final ObjectMapper SHARED_OBJECT_MAPPER = new ObjectMapper();

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
                    governanceRegistry, canaryRouter, lingInfoConverter, permissionService, runtimeCoordinator, SHARED_OBJECT_MAPPER);

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
                    governanceRegistry, canaryRouter, lingInfoConverter, permissionService, runtimeCoordinator, SHARED_OBJECT_MAPPER);

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
                    governanceRegistry, canaryRouter, lingInfoConverter, permissionService, runtimeCoordinator, SHARED_OBJECT_MAPPER);

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
            verify(permissionService).grant("ling1", Capabilities.LING_ENABLE, AccessType.EXECUTE);
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
                    governanceRegistry, canaryRouter, lingInfoConverter, permissionService, runtimeCoordinator, SHARED_OBJECT_MAPPER);

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

            InvocationGovernanceDTO result = service.updateInvocationGovernance("ling1", dto);

            GovernancePolicy saved = storedPatch.get();
            assertEquals(1, saved.getCapabilities().size());
            assertEquals(Integer.valueOf(1200), saved.getInvocation().getTimeoutMs());
            assertEquals(Integer.valueOf(9), result.getRateLimitPerSecond());
            assertEquals(Integer.valueOf(4), result.getMaxConcurrentThreads());
            assertEquals(Integer.valueOf(2), result.getRetryCount());
            assertEquals("fallback-ok", result.getFallbackValue());
            assertEquals(Integer.valueOf(600), result.getCpuBudgetMsPerMinute());
            assertEquals(Integer.valueOf(48), result.getMemoryBudgetMb());

            verify(permissionService).removeLing("ling1");
            verify(permissionService).grant("ling1", Capabilities.CACHE_LOCAL, AccessType.WRITE);
        }
    }

    @Nested
    @DisplayName("受控恢复")
    class RecoverTests {

        @Test
        @DisplayName("恢复状态更新时应触发生命周期引擎恢复")
        void updateStatusShouldTriggerRecover() {
            DashboardService service = new DashboardService(lingFrameConfig, lifecycleEngine, lingRepository,
                    governanceRegistry, canaryRouter, lingInfoConverter, permissionService, runtimeCoordinator, SHARED_OBJECT_MAPPER);

            com.lingframe.core.ling.LingRuntime runtime = org.mockito.Mockito.mock(com.lingframe.core.ling.LingRuntime.class);
            com.lingframe.core.ling.InstancePool instancePool = org.mockito.Mockito.mock(com.lingframe.core.ling.InstancePool.class);
            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.currentStatus()).thenReturn(com.lingframe.core.fsm.RuntimeStatus.DEGRADED);
            when(runtime.getInstancePool()).thenReturn(instancePool);
            when(instancePool.getDefault()).thenReturn(null);
            when(lingInfoConverter.toDTO(eq(runtime), eq(canaryRouter), eq(permissionService), any())).thenReturn(null);

            service.updateStatus("ling1", com.lingframe.core.fsm.RuntimeStatus.RECOVERING, "1.0.0");

            verify(lifecycleEngine).recover("ling1", "1.0.0");
            verify(runtimeCoordinator, never()).transition(eq("ling1"), eq(com.lingframe.core.fsm.RuntimeStatus.RECOVERING));
        }
    }

    @Nested
    @DisplayName("卸载预检返回")
    class UninstallPrecheckTests {

        @Test
        @DisplayName("卸载灵元时应返回结构化风险摘要")
        void uninstallLingShouldReturnStructuredRiskSummary() {
            DashboardService service = new DashboardService(lingFrameConfig, lifecycleEngine, lingRepository,
                    governanceRegistry, canaryRouter, lingInfoConverter, permissionService, runtimeCoordinator, SHARED_OBJECT_MAPPER);
            LeakRiskReport report = LeakRiskReport.riskDetected(
                    "ling1",
                    "1.0.0",
                    "risk detected",
                    Arrays.asList("thread=worker-1"),
                    "test");
            when(lifecycleEngine.undeployWithReport("ling1"))
                    .thenReturn(LingUninstallResult.triggered("ling1", null, Arrays.asList(report)));

            LingUninstallResultDTO result = service.uninstallLing("ling1");

            assertNotNull(result);
            assertTrue(result.isUninstallTriggered());
            assertEquals(LeakRiskLevel.RISK_DETECTED, result.getOverallRiskLevel());
            assertEquals(1, result.getReports().size());
            assertEquals("thread=worker-1", result.getReports().get(0).getDetails().get(0));
            verify(canaryRouter).removeCanaryConfig("ling1");
        }

        @Test
        @DisplayName("按版本卸载时应返回对应版本的风险摘要")
        void uninstallLingVersionShouldReturnVersionScopedRiskSummary() {
            DashboardService service = new DashboardService(lingFrameConfig, lifecycleEngine, lingRepository,
                    governanceRegistry, canaryRouter, lingInfoConverter, permissionService, runtimeCoordinator, SHARED_OBJECT_MAPPER);
            LeakRiskReport report = LeakRiskReport.checkFailed(
                    "ling1",
                    "1.0.1",
                    "check failed",
                    Arrays.asList("IllegalStateException"),
                    "test");
            when(lifecycleEngine.undeployWithReport("ling1", "1.0.1"))
                    .thenReturn(LingUninstallResult.triggered("ling1", "1.0.1", Arrays.asList(report)));

            LingUninstallResultDTO result = service.uninstallLing("ling1", "1.0.1");

            assertNotNull(result);
            assertTrue(result.isUninstallTriggered());
            assertEquals("1.0.1", result.getVersion());
            assertEquals(LeakRiskLevel.CHECK_FAILED, result.getOverallRiskLevel());
            assertEquals(1, result.getReports().size());
            verify(canaryRouter).removeCanaryConfig("ling1");
        }
    }
}
