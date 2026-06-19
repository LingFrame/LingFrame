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
import com.lingframe.core.router.CanaryRouter;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.dashboard.dto.LingInfoDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("LingInfoConverter 测试")
class LingInfoConverterTest {

    @Test
    @DisplayName("应提取细粒度 SQL 与 Redis capability 并保留本地缓存命名空间说明")
    void shouldExposeFineGrainedCapabilities() {
        LingRuntime runtime = mock(LingRuntime.class);
        InstancePool instancePool = mock(InstancePool.class);
        CanaryRouter canaryRouter = mock(CanaryRouter.class);
        PermissionService permissionService = mock(PermissionService.class);

        when(runtime.getLingId()).thenReturn("ling-a");
        when(runtime.currentStatus()).thenReturn(com.lingframe.core.fsm.RuntimeStatus.ACTIVE);
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(runtime.getInstalledAt()).thenReturn(123L);
        when(instancePool.getActiveInstances()).thenReturn(Collections.<LingInstance>emptyList());
        when(instancePool.getAllInstances()).thenReturn(Collections.<LingInstance>emptyList());
        when(canaryRouter.getCanaryPercent("ling-a")).thenReturn(0);

        when(permissionService.getPermission("ling-a", Capabilities.STORAGE_SQL))
                .thenReturn(PermissionInfo.permanent("ling-a", Capabilities.STORAGE_SQL, AccessType.WRITE, "test"));
        when(permissionService.getPermission("ling-a", Capabilities.CACHE_LOCAL))
                .thenReturn(PermissionInfo.permanent("ling-a", Capabilities.CACHE_LOCAL, AccessType.READ, "test"));

        GovernancePolicy policy = new GovernancePolicy();
        policy.setCapabilities(java.util.Arrays.asList(
                GovernancePolicy.CapabilityRule.builder()
                        .capability("storage:sql:table:users")
                        .accessType(AccessType.READ.name())
                        .build(),
                GovernancePolicy.CapabilityRule.builder()
                        .capability("cache:redis:key:user:*")
                        .accessType(AccessType.WRITE.name())
                        .build(),
                GovernancePolicy.CapabilityRule.builder()
                        .capability("custom:export")
                        .accessType(AccessType.EXECUTE.name())
                        .build(),
                GovernancePolicy.CapabilityRule.builder()
                        .capability("ipc:order-ling")
                        .accessType(AccessType.EXECUTE.name())
                        .build()));
        policy.getInvocation().setCpuBudgetMsPerMinute(900);
        policy.getInvocation().setMemoryBudgetMb(64);

        LingInfoDTO dto = new LingInfoConverter().toDTO(runtime, canaryRouter, permissionService, policy);

        assertEquals(1, dto.getPermissions().getSqlCapabilities().size());
        assertEquals("storage:sql:table:users", dto.getPermissions().getSqlCapabilities().get(0));
        assertEquals(1, dto.getPermissions().getRedisCapabilities().size());
        assertEquals("cache:redis:key:user:*", dto.getPermissions().getRedisCapabilities().get(0));
        assertEquals(1, dto.getPermissions().getExtraCapabilities().size());
        assertEquals("custom:export", dto.getPermissions().getExtraCapabilities().get(0));
        assertTrue(dto.getPermissions().getIpcServices().contains("order-ling"));
        assertEquals("lingId + cacheName + rawKey", dto.getPermissions().getLocalCacheNamespaceStrategy());
        assertEquals(900, dto.getInvocationGovernance().getCpuBudgetMsPerMinute());
        assertEquals(64, dto.getInvocationGovernance().getMemoryBudgetMb());
    }

    @Test
    @DisplayName("reload 时序窗口：dyingQueue 中的旧实例不应出现在版本列表")
    void shouldFilterDestroyedInstanceDuringReload() {
        // 模拟 reload 场景：
        // - activePool 有新实例（READY）
        // - dyingQueue 有旧实例（STOPPING 状态，仍在 drain）
        // 期望：versionDetails 只包含 activePool 中的新实例
        LingRuntime runtime = mock(LingRuntime.class);
        InstancePool instancePool = mock(InstancePool.class);
        CanaryRouter canaryRouter = mock(CanaryRouter.class);
        PermissionService permissionService = mock(PermissionService.class);
        EventBus eventBus = mock(EventBus.class);

        when(runtime.getLingId()).thenReturn("ling-a");
        when(runtime.currentStatus()).thenReturn(RuntimeStatus.ACTIVE);
        when(runtime.getInstancePool()).thenReturn(instancePool);
        when(runtime.getInstalledAt()).thenReturn(123L);
        when(canaryRouter.getCanaryPercent("ling-a")).thenReturn(0);

        // 新实例：正常 READY 状态
        LingDefinition newDef = new LingDefinition();
        newDef.setId("ling-a");
        newDef.setVersion("1.0.0-reload-1");
        LingContainer newContainer = mock(LingContainer.class);
        when(newContainer.isActive()).thenReturn(true);
        LingInstance newInstance = new LingInstance(newContainer, newDef, eventBus);

        // 旧实例：在 dyingQueue 中，STOPPING 状态（drain 中）
        LingInstance oldInstance = mock(LingInstance.class);
        when(oldInstance.getDefinition()).thenReturn(new LingDefinition());
        when(oldInstance.getVersion()).thenReturn("1.0.0");
        when(oldInstance.currentStatus()).thenReturn(com.lingframe.core.fsm.InstanceStatus.STOPPING);

        // activeInstances 只含新实例，getAllInstances 含两者
        when(instancePool.getActiveInstances()).thenReturn(Collections.singletonList(newInstance));
        when(instancePool.getDefault()).thenReturn(newInstance);

        LingInfoDTO dto = new LingInfoConverter().toDTO(runtime, canaryRouter, permissionService, null);

        // 只应返回 1 个版本（新实例），dyingQueue 中的旧实例不展示
        assertEquals(1, dto.getVersionDetails().size());
        assertEquals("1.0.0-reload-1", dto.getVersionDetails().get(0).getVersion());
    }
}
