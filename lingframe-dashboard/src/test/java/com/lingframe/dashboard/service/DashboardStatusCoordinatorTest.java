package com.lingframe.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.governance.GovernanceAdminService;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DashboardStatusCoordinator 测试")
class DashboardStatusCoordinatorTest {

    // 测试类共享 ObjectMapper 单例，避免每个测试方法都 new 一个实例
    private static final ObjectMapper SHARED_OBJECT_MAPPER = new ObjectMapper();

    @Test
    @DisplayName("激活成功时应写入时间线（灵元已显式配置 capabilities）")
    void shouldWriteTimelineWhenActivatingWithCapabilitiesConfigured() {
        LingLifecycleEngine lifecycleEngine = mock(LingLifecycleEngine.class);
        PermissionService permissionService = mock(PermissionService.class);
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(new EventBus());
        GovernanceAdminService governanceAdmin = mock(GovernanceAdminService.class);

        // 提供非空 capabilities，满足激活前置校验（不再自动注入默认能力）
        GovernancePolicy effectivePolicy = GovernancePolicy.builder()
                .capabilities(Arrays.asList(
                        GovernancePolicy.CapabilityRule.builder()
                                .capability(Capabilities.LING_ENABLE)
                                .accessType(AccessType.EXECUTE.name())
                                .build()))
                .build();
        when(governanceAdmin.getEffectivePolicy("ling1")).thenReturn(effectivePolicy);

        runtimeCoordinator.register("ling1");

        DashboardGovernanceSupport governanceSupport =
                new DashboardGovernanceSupport(governanceAdmin, permissionService, SHARED_OBJECT_MAPPER);
        DashboardLifecycleEventStore eventStore = new DashboardLifecycleEventStore();
        DashboardStatusCoordinator coordinator = new DashboardStatusCoordinator(
                lifecycleEngine, permissionService, runtimeCoordinator, governanceSupport, eventStore);

        coordinator.updateStatus("ling1", RuntimeStatus.INACTIVE, RuntimeStatus.ACTIVE, "1.0.0");

        // 不再自动持久化补丁，时间线应包含一条 ACTIVE 事件
        verify(governanceAdmin, never())
                .persistPolicyPatch(eq("ling1"), any(GovernancePolicy.class));
        List<DashboardService.LifecycleEvent> events = eventStore.getEvents("ling1");
        assertEquals(1, events.size());
        assertEquals("ACTIVE", events.get(0).getType());
    }

    @Test
    @DisplayName("未配置 capabilities 时激活应失败且不写入时间线")
    void shouldFailActivationWhenNoCapabilitiesConfigured() {
        LingLifecycleEngine lifecycleEngine = mock(LingLifecycleEngine.class);
        PermissionService permissionService = mock(PermissionService.class);
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(new EventBus());
        GovernanceAdminService governanceAdmin = mock(GovernanceAdminService.class);

        // 未配置 capabilities
        when(governanceAdmin.getEffectivePolicy("ling1")).thenReturn(null);
        runtimeCoordinator.register("ling1");

        DashboardGovernanceSupport governanceSupport =
                new DashboardGovernanceSupport(governanceAdmin, permissionService, SHARED_OBJECT_MAPPER);
        DashboardLifecycleEventStore eventStore = new DashboardLifecycleEventStore();
        DashboardStatusCoordinator coordinator = new DashboardStatusCoordinator(
                lifecycleEngine, permissionService, runtimeCoordinator, governanceSupport, eventStore);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> coordinator.updateStatus("ling1", RuntimeStatus.INACTIVE, RuntimeStatus.ACTIVE, "1.0.0"));

        assertTrue(ex.getMessage().contains("Cannot activate"));
        assertTrue(ex.getMessage().contains("no capabilities configured"));
        // 激活失败不应触发任何生命周期编排或时间线写入
        verify(lifecycleEngine, never()).recover(any(), any());
        assertTrue(eventStore.getEvents("ling1").isEmpty());
    }

    @Test
    @DisplayName("恢复时应触发生命周期恢复并追加两条时间线")
    void shouldRecordRecoverFlow() {
        LingLifecycleEngine lifecycleEngine = mock(LingLifecycleEngine.class);
        PermissionService permissionService = mock(PermissionService.class);
        RuntimeCoordinator runtimeCoordinator = mock(RuntimeCoordinator.class);
        DashboardLifecycleEventStore store = new DashboardLifecycleEventStore();
        DashboardStatusCoordinator coordinator = new DashboardStatusCoordinator(
                lifecycleEngine,
                permissionService,
                runtimeCoordinator,
                mock(DashboardGovernanceSupport.class),
                store);

        coordinator.updateStatus("ling1", RuntimeStatus.DEGRADED, RuntimeStatus.RECOVERING, "1.0.0");

        verify(lifecycleEngine).recover("ling1", "1.0.0");
        List<DashboardService.LifecycleEvent> events = store.getEvents("ling1");
        assertEquals(2, events.size());
        assertEquals("RECOVERING", events.get(0).getType());
        assertEquals("ACTIVE", events.get(1).getType());
        assertTrue(events.get(0).getDescription().contains("恢复流程"));
    }
}
