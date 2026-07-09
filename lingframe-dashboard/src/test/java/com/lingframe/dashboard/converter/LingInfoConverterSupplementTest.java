package com.lingframe.dashboard.converter;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionInfo;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRuntimeConfig;
import com.lingframe.core.router.CanaryRouter;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.dashboard.dto.LingInfoDTO;
import com.lingframe.dashboard.dto.TrafficStatsDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LingInfoConverter 补充测试
 * <p>
 * 覆盖 toTrafficStats（完全未覆盖）以及 extractInvocationGovernance 的
 * runtime.getConfig() 回退分支、extractPermissions 的 null policy 分支。
 */
@DisplayName("LingInfoConverter 补充测试")
class LingInfoConverterSupplementTest {

    // ==================== toTrafficStats ====================

    @Nested
    @DisplayName("toTrafficStats")
    class ToTrafficStatsTests {

        @Test
        @DisplayName("total>0 时应正确计算百分比")
        void shouldCalculatePercentagesWhenTotalGreaterThanZero() {
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.getLingId()).thenReturn("ling1");
            when(runtime.getTotalRequests()).thenReturn(new AtomicLong(100));
            when(runtime.getStableRequests()).thenReturn(new AtomicLong(80));
            when(runtime.getCanaryRequests()).thenReturn(new AtomicLong(20));
            when(runtime.getActiveRequests()).thenReturn(new AtomicLong(5));
            when(runtime.getStatsWindowStart()).thenReturn(123L);

            TrafficStatsDTO dto = new LingInfoConverter().toTrafficStats(runtime);

            assertEquals("ling1", dto.getLingId());
            assertEquals(100, dto.getTotalRequests());
            assertEquals(80, dto.getV1Requests());
            assertEquals(20, dto.getV2Requests());
            assertEquals(5, dto.getActiveRequests());
            assertEquals(80.0, dto.getV1Percent(), 0.001);
            assertEquals(20.0, dto.getV2Percent(), 0.001);
            assertEquals(123L, dto.getWindowStartTime());
        }

        @Test
        @DisplayName("total=0 时百分比应为 0 避免除零")
        void shouldReturnZeroPercentWhenTotalIsZero() {
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.getLingId()).thenReturn("ling1");
            when(runtime.getTotalRequests()).thenReturn(new AtomicLong(0));
            when(runtime.getStableRequests()).thenReturn(new AtomicLong(0));
            when(runtime.getCanaryRequests()).thenReturn(new AtomicLong(0));
            when(runtime.getActiveRequests()).thenReturn(new AtomicLong(0));
            when(runtime.getStatsWindowStart()).thenReturn(0L);

            TrafficStatsDTO dto = new LingInfoConverter().toTrafficStats(runtime);

            assertEquals(0, dto.getTotalRequests());
            assertEquals(0.0, dto.getV1Percent(), 0.001);
            assertEquals(0.0, dto.getV2Percent(), 0.001);
        }
    }

    // ==================== extractInvocationGovernance 回退分支 ====================

    @Nested
    @DisplayName("调用治理配置回退")
    class InvocationGovernanceFallbackTests {

        @Test
        @DisplayName("policy 为 null 且 runtime 有 config 时应回退到运行时默认值")
        void shouldFallbackToRuntimeConfigDefaultsWhenPolicyNull() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            CanaryRouter router = mock(CanaryRouter.class);
            PermissionService permSvc = mock(PermissionService.class);
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .defaultTimeoutMs(5000)
                    .rateLimitPerSecond(20)
                    .bulkheadMaxConcurrent(50)
                    .build();

            when(runtime.getLingId()).thenReturn("ling1");
            when(runtime.currentStatus()).thenReturn(RuntimeStatus.ACTIVE);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(runtime.getInstalledAt()).thenReturn(123L);
            when(runtime.getConfig()).thenReturn(config);
            when(pool.getActiveInstances()).thenReturn(Collections.<LingInstance>emptyList());
            when(router.getCanaryPercent("ling1")).thenReturn(0);

            LingInfoDTO dto = new LingInfoConverter().toDTO(runtime, router, permSvc, null);

            assertNotNull(dto.getInvocationGovernance());
            assertEquals(Integer.valueOf(5000), dto.getInvocationGovernance().getTimeoutMs());
            assertEquals(Integer.valueOf(20), dto.getInvocationGovernance().getRateLimitPerSecond());
            assertEquals(Integer.valueOf(50), dto.getInvocationGovernance().getMaxConcurrentThreads());
        }

        @Test
        @DisplayName("policy 中已设置的值应优先于运行时默认值")
        void shouldPreferPolicyValuesOverRuntimeDefaults() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            CanaryRouter router = mock(CanaryRouter.class);
            PermissionService permSvc = mock(PermissionService.class);
            LingRuntimeConfig config = LingRuntimeConfig.builder()
                    .defaultTimeoutMs(5000)
                    .rateLimitPerSecond(20)
                    .bulkheadMaxConcurrent(50)
                    .build();

            when(runtime.getLingId()).thenReturn("ling1");
            when(runtime.currentStatus()).thenReturn(RuntimeStatus.ACTIVE);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(runtime.getInstalledAt()).thenReturn(123L);
            when(runtime.getConfig()).thenReturn(config);
            when(pool.getActiveInstances()).thenReturn(Collections.<LingInstance>emptyList());
            when(router.getCanaryPercent("ling1")).thenReturn(0);

            GovernancePolicy policy = new GovernancePolicy();
            policy.getInvocation().setTimeoutMs(1000);
            // rateLimitPerSecond 和 maxConcurrentThreads 未设置，应回退到 config

            LingInfoDTO dto = new LingInfoConverter().toDTO(runtime, router, permSvc, policy);

            assertEquals(Integer.valueOf(1000), dto.getInvocationGovernance().getTimeoutMs());
            assertEquals(Integer.valueOf(20), dto.getInvocationGovernance().getRateLimitPerSecond());
            assertEquals(Integer.valueOf(50), dto.getInvocationGovernance().getMaxConcurrentThreads());
        }

        @Test
        @DisplayName("runtime.getConfig() 为 null 时应使用 policy 中的值（可能为 null）")
        void shouldUsePolicyValuesWhenRuntimeConfigNull() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            CanaryRouter router = mock(CanaryRouter.class);
            PermissionService permSvc = mock(PermissionService.class);

            when(runtime.getLingId()).thenReturn("ling1");
            when(runtime.currentStatus()).thenReturn(RuntimeStatus.ACTIVE);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(runtime.getInstalledAt()).thenReturn(123L);
            when(runtime.getConfig()).thenReturn(null);
            when(pool.getActiveInstances()).thenReturn(Collections.<LingInstance>emptyList());
            when(router.getCanaryPercent("ling1")).thenReturn(0);

            // policy null → 所有 invocation 字段 null，且无 config 回退
            LingInfoDTO dto = new LingInfoConverter().toDTO(runtime, router, permSvc, null);

            assertNotNull(dto.getInvocationGovernance());
            assertEquals(null, dto.getInvocationGovernance().getTimeoutMs());
            assertEquals(null, dto.getInvocationGovernance().getRateLimitPerSecond());
        }
    }

    // ==================== extractPermissions null 分支 ====================

    @Nested
    @DisplayName("权限提取 null 分支")
    class PermissionsNullBranchTests {

        @Test
        @DisplayName("policy 为 null 时应返回全 false 的权限和空 capability 列表")
        void shouldReturnEmptyPermissionsWhenPolicyNull() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            CanaryRouter router = mock(CanaryRouter.class);
            PermissionService permSvc = mock(PermissionService.class);

            when(runtime.getLingId()).thenReturn("ling1");
            when(runtime.currentStatus()).thenReturn(RuntimeStatus.ACTIVE);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(runtime.getInstalledAt()).thenReturn(123L);
            when(pool.getActiveInstances()).thenReturn(Collections.<LingInstance>emptyList());
            when(router.getCanaryPercent("ling1")).thenReturn(0);
            // permissionService.getPermission 返回 null（mock 默认）

            LingInfoDTO dto = new LingInfoConverter().toDTO(runtime, router, permSvc, null);

            assertNotNull(dto.getPermissions());
            assertFalse(dto.getPermissions().isDbRead());
            assertFalse(dto.getPermissions().isDbWrite());
            assertFalse(dto.getPermissions().isCacheRead());
            assertFalse(dto.getPermissions().isCacheWrite());
            assertTrue(dto.getPermissions().getIpcServices().isEmpty());
            assertTrue(dto.getPermissions().getSqlCapabilities().isEmpty());
        }

        @Test
        @DisplayName("policy capabilities 含 null rule 时应跳过")
        void shouldSkipNullCapabilityRules() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            CanaryRouter router = mock(CanaryRouter.class);
            PermissionService permSvc = mock(PermissionService.class);

            when(runtime.getLingId()).thenReturn("ling1");
            when(runtime.currentStatus()).thenReturn(RuntimeStatus.ACTIVE);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(runtime.getInstalledAt()).thenReturn(123L);
            when(pool.getActiveInstances()).thenReturn(Collections.<LingInstance>emptyList());
            when(router.getCanaryPercent("ling1")).thenReturn(0);

            GovernancePolicy policy = new GovernancePolicy();
            policy.setCapabilities(Collections.singletonList(
                    GovernancePolicy.CapabilityRule.builder()
                            .capability(null) // null capability
                            .accessType(AccessType.EXECUTE.name())
                            .build()));

            LingInfoDTO dto = new LingInfoConverter().toDTO(runtime, router, permSvc, policy);

            // null capability 的 rule 应被跳过，所有 capability 列表为空
            assertTrue(dto.getPermissions().getExtraCapabilities().isEmpty());
        }

        @Test
        @DisplayName("标准 capability（STORAGE_SQL/CACHE_LOCAL/LING_ENABLE）应被归类到权限位而非 extra")
        void shouldCategorizeStandardCapabilitiesCorrectly() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            CanaryRouter router = mock(CanaryRouter.class);
            PermissionService permSvc = mock(PermissionService.class);

            when(runtime.getLingId()).thenReturn("ling1");
            when(runtime.currentStatus()).thenReturn(RuntimeStatus.ACTIVE);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(runtime.getInstalledAt()).thenReturn(123L);
            when(pool.getActiveInstances()).thenReturn(Collections.<LingInstance>emptyList());
            when(router.getCanaryPercent("ling1")).thenReturn(0);
            when(permSvc.getPermission("ling1", Capabilities.STORAGE_SQL))
                    .thenReturn(PermissionInfo.permanent("ling1", Capabilities.STORAGE_SQL, AccessType.WRITE, "test"));
            when(permSvc.getPermission("ling1", Capabilities.CACHE_LOCAL))
                    .thenReturn(PermissionInfo.permanent("ling1", Capabilities.CACHE_LOCAL, AccessType.READ, "test"));

            GovernancePolicy policy = new GovernancePolicy();
            policy.setCapabilities(java.util.Arrays.asList(
                    GovernancePolicy.CapabilityRule.builder()
                            .capability(Capabilities.STORAGE_SQL)
                            .accessType(AccessType.WRITE.name())
                            .build(),
                    GovernancePolicy.CapabilityRule.builder()
                            .capability(Capabilities.CACHE_LOCAL)
                            .accessType(AccessType.READ.name())
                            .build(),
                    GovernancePolicy.CapabilityRule.builder()
                            .capability(Capabilities.LING_ENABLE)
                            .accessType(AccessType.EXECUTE.name())
                            .build()));

            LingInfoDTO dto = new LingInfoConverter().toDTO(runtime, router, permSvc, policy);

            // 三个标准 capability 不应出现在 extraCapabilities
            assertTrue(dto.getPermissions().getExtraCapabilities().isEmpty());
            assertTrue(dto.getPermissions().isDbWrite());
            assertTrue(dto.getPermissions().isCacheRead());
        }
    }

    // ==================== isCanary 字符串分支 ====================

    @Nested
    @DisplayName("isCanary 字符串识别")
    class CanaryStringTests {

        @Test
        @DisplayName("canary 属性为字符串 'true' 时应识别为 canary")
        void shouldRecognizeStringTrueAsCanary() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            CanaryRouter router = mock(CanaryRouter.class);
            PermissionService permSvc = mock(PermissionService.class);
            EventBus eventBus = mock(EventBus.class);

            when(runtime.getLingId()).thenReturn("ling1");
            when(runtime.currentStatus()).thenReturn(RuntimeStatus.ACTIVE);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(runtime.getInstalledAt()).thenReturn(123L);
            when(router.getCanaryPercent("ling1")).thenReturn(50);

            com.lingframe.api.config.LingDefinition def = new com.lingframe.api.config.LingDefinition();
            def.setId("ling1");
            def.setVersion("2.0.0");
            Map<String, Object> props = new HashMap<String, Object>();
            props.put("canary", "true"); // 字符串而非 boolean
            def.setProperties(props);
            LingContainer container = mock(LingContainer.class);
            when(container.isActive()).thenReturn(true);
            LingInstance instance = new LingInstance(container, def, eventBus);

            when(pool.getActiveInstances()).thenReturn(Collections.singletonList(instance));
            when(pool.getDefault()).thenReturn(instance);

            LingInfoDTO dto = new LingInfoConverter().toDTO(runtime, router, permSvc, null);

            assertEquals(1, dto.getVersionDetails().size());
            assertTrue(dto.getVersionDetails().get(0).getIsCanary());
        }
    }
}
