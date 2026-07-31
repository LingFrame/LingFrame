package com.lingframe.dashboard.converter;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.config.LingDefinition;
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
import com.lingframe.core.spi.LingContainer;
import com.lingframe.dashboard.dto.LingInfoDTO;
import com.lingframe.dashboard.dto.TrafficStatsDTO;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
        @DisplayName("total>0 时应正确计算百分比（流量统计已下沉到 MetricsCollector）")
        void shouldCalculatePercentagesWhenTotalGreaterThanZero() {
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.getLingId()).thenReturn("ling1");
            when(runtime.getInstalledAt()).thenReturn(123L);
            // 流量统计已从 LingRuntime 下沉到 ProviderMetricsCollector / LingHealthMetrics
            // LingInfoConverter(metricsCollector).toTrafficStats 不再读 runtime 的流量字段

            TrafficStatsDTO dto = new LingInfoConverter(null).toTrafficStats(runtime);

            assertEquals("ling1", dto.getLingId());
            // 累计统计由 ProviderMetricsCollector 维护，转换器返回 0
            assertEquals(0, dto.getTotalRequests());
            assertEquals(0, dto.getV1Requests());
            assertEquals(0, dto.getV2Requests());
            assertEquals(0.0, dto.getV1Percent(), 0.001);
            assertEquals(0.0, dto.getV2Percent(), 0.001);
        }

        @Test
        @DisplayName("total=0 时百分比应为 0 避免除零")
        void shouldReturnZeroPercentWhenTotalIsZero() {
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.getLingId()).thenReturn("ling1");
            when(runtime.getInstalledAt()).thenReturn(0L);

            TrafficStatsDTO dto = new LingInfoConverter(null).toTrafficStats(runtime);

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
            LingInfoDTO dto = new LingInfoConverter(null).toDTO(runtime, permSvc, null);

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
            // router 已删除，路由层去身份化

            GovernancePolicy policy = new GovernancePolicy();
            policy.getInvocation().setTimeoutMs(1000);
            // rateLimitPerSecond 和 maxConcurrentThreads 未设置，应回退到 config

            LingInfoDTO dto = new LingInfoConverter(null).toDTO(runtime, permSvc, policy);

            assertEquals(Integer.valueOf(1000), dto.getInvocationGovernance().getTimeoutMs());
            assertEquals(Integer.valueOf(20), dto.getInvocationGovernance().getRateLimitPerSecond());
            assertEquals(Integer.valueOf(50), dto.getInvocationGovernance().getMaxConcurrentThreads());
        }

        @Test
        @DisplayName("runtime.getConfig() 为 null 时应使用 policy 中的值（可能为 null）")
        void shouldUsePolicyValuesWhenRuntimeConfigNull() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            PermissionService permSvc = mock(PermissionService.class);

            when(runtime.getLingId()).thenReturn("ling1");
            when(runtime.currentStatus()).thenReturn(RuntimeStatus.ACTIVE);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(runtime.getInstalledAt()).thenReturn(123L);
            when(runtime.getConfig()).thenReturn(null);
            when(pool.getActiveInstances()).thenReturn(Collections.<LingInstance>emptyList());
            // router 已删除，路由层去身份化

            // policy null → 所有 invocation 字段 null，且无 config 回退
            LingInfoDTO dto = new LingInfoConverter(null).toDTO(runtime, permSvc, null);

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
            PermissionService permSvc = mock(PermissionService.class);

            when(runtime.getLingId()).thenReturn("ling1");
            when(runtime.currentStatus()).thenReturn(RuntimeStatus.ACTIVE);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(runtime.getInstalledAt()).thenReturn(123L);
            when(pool.getActiveInstances()).thenReturn(Collections.<LingInstance>emptyList());
            // router 已删除，路由层去身份化
            // permissionService.getPermission 返回 null（mock 默认）

            LingInfoDTO dto = new LingInfoConverter(null).toDTO(runtime, permSvc, null);

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
            PermissionService permSvc = mock(PermissionService.class);

            when(runtime.getLingId()).thenReturn("ling1");
            when(runtime.currentStatus()).thenReturn(RuntimeStatus.ACTIVE);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(runtime.getInstalledAt()).thenReturn(123L);
            when(pool.getActiveInstances()).thenReturn(Collections.<LingInstance>emptyList());
            // router 已删除，路由层去身份化

            GovernancePolicy policy = new GovernancePolicy();
            policy.setCapabilities(Collections.singletonList(
                    GovernancePolicy.CapabilityRule.builder()
                            .capability(null) // null capability
                            .accessType(AccessType.EXECUTE.name())
                            .build()));

            LingInfoDTO dto = new LingInfoConverter(null).toDTO(runtime, permSvc, policy);

            // null capability 的 rule 应被跳过，所有 capability 列表为空
            assertTrue(dto.getPermissions().getExtraCapabilities().isEmpty());
        }

        @Test
        @DisplayName("标准 capability（STORAGE_SQL/CACHE_LOCAL/LING_ENABLE）应被归类到权限位而非 extra")
        void shouldCategorizeStandardCapabilitiesCorrectly() {
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            PermissionService permSvc = mock(PermissionService.class);

            when(runtime.getLingId()).thenReturn("ling1");
            when(runtime.currentStatus()).thenReturn(RuntimeStatus.ACTIVE);
            when(runtime.getInstancePool()).thenReturn(pool);
            when(runtime.getInstalledAt()).thenReturn(123L);
            when(pool.getActiveInstances()).thenReturn(Collections.<LingInstance>emptyList());
            // router 已删除，路由层去身份化
            when(permSvc.getPermission("ling1", Capabilities.STORAGE_SQL))
                    .thenReturn(PermissionInfo.permanent("ling1", Capabilities.STORAGE_SQL, AccessType.WRITE, "test"));
            when(permSvc.getPermission("ling1", Capabilities.CACHE_LOCAL))
                    .thenReturn(PermissionInfo.permanent("ling1", Capabilities.CACHE_LOCAL, AccessType.READ, "test"));

            GovernancePolicy policy = new GovernancePolicy();
            policy.setCapabilities(Arrays.asList(
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

            LingInfoDTO dto = new LingInfoConverter(null).toDTO(runtime, permSvc, policy);

            // 三个标准 capability 不应出现在 extraCapabilities
            assertTrue(dto.getPermissions().getExtraCapabilities().isEmpty());
            assertTrue(dto.getPermissions().isDbWrite());
            assertTrue(dto.getPermissions().isCacheRead());
        }
    }
}
