package com.lingframe.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DashboardStatusCoordinator 测试")
class DashboardStatusCoordinatorTest {

    // 测试类共享 ObjectMapper 单例，避免每个测试方法都 new 一个实例
    private static final ObjectMapper SHARED_OBJECT_MAPPER = new ObjectMapper();

    @Test
    @DisplayName("激活时应初始化默认能力并写入时间线")
    void shouldInitializeDefaultCapabilitiesWhenActivating() {
        LingLifecycleEngine lifecycleEngine = mock(LingLifecycleEngine.class);
        PermissionService permissionService = mock(PermissionService.class);
        RuntimeCoordinator runtimeCoordinator = new RuntimeCoordinator(new EventBus());
        LingRepository lingRepository = mock(LingRepository.class);
        LocalGovernanceRegistry governanceRegistry = mock(LocalGovernanceRegistry.class);

        AtomicReference<GovernancePolicy> storedPatch = new AtomicReference<GovernancePolicy>();
        when(governanceRegistry.getPatch("ling1")).thenAnswer(invocation -> storedPatch.get());
        doAnswer(invocation -> {
            storedPatch.set(((GovernancePolicy) invocation.getArgument(1)).copy());
            return null;
        }).when(governanceRegistry).updatePatch(eq("ling1"), any(GovernancePolicy.class));
        runtimeCoordinator.register("ling1");

        DashboardGovernanceSupport governanceSupport =
                new DashboardGovernanceSupport(lingRepository, governanceRegistry, permissionService, SHARED_OBJECT_MAPPER);
        DashboardLifecycleEventStore eventStore = new DashboardLifecycleEventStore();
        DashboardStatusCoordinator coordinator = new DashboardStatusCoordinator(
                lifecycleEngine, permissionService, runtimeCoordinator, governanceSupport, eventStore);

        coordinator.updateStatus("ling1", RuntimeStatus.INACTIVE, RuntimeStatus.ACTIVE, "1.0.0");

        verify(permissionService).removeLing("ling1");
        verify(permissionService).grant("ling1", Capabilities.STORAGE_SQL, AccessType.WRITE);
        verify(permissionService).grant("ling1", Capabilities.CACHE_LOCAL, AccessType.WRITE);
        verify(permissionService).grant("ling1", Capabilities.Ling_ENABLE, AccessType.EXECUTE);
        List<DashboardService.LifecycleEvent> events = eventStore.getEvents("ling1");
        assertEquals(1, events.size());
        assertEquals("ACTIVE", events.get(0).getType());
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
