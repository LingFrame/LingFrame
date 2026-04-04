package com.lingframe.dashboard.converter;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionInfo;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.router.CanaryRouter;
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
}
