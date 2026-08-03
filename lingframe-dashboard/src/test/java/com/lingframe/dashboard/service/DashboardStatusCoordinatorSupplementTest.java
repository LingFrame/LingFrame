package com.lingframe.dashboard.service;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.fsm.TransitionRecord;
import com.lingframe.core.fsm.TransitionResult;
import com.lingframe.core.ling.LingLifecycleEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DashboardStatusCoordinator} 的补充测试。
 * <p>
 * 已有 {@link DashboardStatusCoordinatorTest} 通过真实 RuntimeCoordinator / DashboardGovernanceSupport
 * 覆盖了“激活时初始化默认能力”和“恢复流程时间线”两条主路径；本类用纯 Mock 隔离其它依赖，
 * 专门补齐 switch 各分支、异常路径、recover 的 version 分支以及委托方法等尚未触达的场景。
 * <p>
 * 注意：{@link TransitionResult} 为 final 类，且项目仅引入 mockito-core（无 inline mock maker），
 * 无法直接 Mock，故通过反射调用其包级静态工厂方法构造真实实例。
 */
@DisplayName("DashboardStatusCoordinator 补充测试 - 覆盖未触达分支")
class DashboardStatusCoordinatorSupplementTest {

    private LingLifecycleEngine lifecycleEngine;
    private PermissionService permissionService;
    private RuntimeCoordinator runtimeCoordinator;
    private DashboardGovernanceSupport governanceSupport;
    private DashboardLifecycleEventStore eventStore;
    private DashboardLingOperations lingOperations;
    private DashboardStatusCoordinator coordinator;

    @BeforeEach
    void setUp() {
        lifecycleEngine = mock(LingLifecycleEngine.class);
        permissionService = mock(PermissionService.class);
        runtimeCoordinator = mock(RuntimeCoordinator.class);
        governanceSupport = mock(DashboardGovernanceSupport.class);
        // 事件存储使用真实实现，便于断言时间线内容
        eventStore = new DashboardLifecycleEventStore();
        lingOperations = mock(DashboardLingOperations.class);
        coordinator = new DashboardStatusCoordinator(
                lifecycleEngine, permissionService, runtimeCoordinator, governanceSupport, eventStore, lingOperations);
    }

    // ==================== updateStatus 分支 ====================

    @Test
    @DisplayName("REMOVED 分支应复用 uninstallLing 完整卸载流程（含迁移状态清理与 DEAD 事件）")
    void shouldUndeployWhenRemoved() {
        when(lingOperations.uninstallLing("ling1")).thenReturn(
                com.lingframe.core.ling.LingUninstallResult.triggered("ling1", null, Collections.emptyList()));

        coordinator.updateStatus("ling1", RuntimeStatus.ACTIVE, RuntimeStatus.REMOVED, "1.0.0");

        // REMOVED 与 Dashboard 卸载入口同路（C7）：委托完整 uninstall 流程，而非裸 undeploy
        verify(lingOperations).uninstallLing("ling1");
        verify(lifecycleEngine, never()).undeploy("ling1");
        // REMOVED 不应触碰运行时状态机或权限服务
        verifyNoMoreInteractions(runtimeCoordinator);
        verifyNoMoreInteractions(permissionService);
    }

    @Test
    @DisplayName("未支持的 newStatus（如 DEGRADED）应抛 IllegalArgumentException")
    void shouldThrowWhenUnsupportedStatus() {
        // DEGRADED 不在 switch 的 case 列表中（ACTIVE/INACTIVE/RECOVERING/REMOVED），走 default
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> coordinator.updateStatus("ling1", RuntimeStatus.ACTIVE, RuntimeStatus.DEGRADED, "1.0.0"));

        assertTrue(ex.getMessage().contains("Unsupported status"));
        assertTrue(ex.getMessage().contains("DEGRADED"));
    }

    // ==================== activateLing ====================

    @Test
    @DisplayName("从 INACTIVE 激活成功应调用 lifecycleEngine.recover 并记录 ACTIVE 事件")
    void shouldRecoverAndRecordEventWhenActivatingFromInactive() {
        // 提供非空 capabilities，避免触发 persistPolicyPatch，聚焦于 recover 路径
        GovernancePolicy policy = GovernancePolicy.builder()
                .capabilities(Arrays.asList(
                        GovernancePolicy.CapabilityRule.builder()
                                .capability(Capabilities.LING_ENABLE)
                                .accessType(AccessType.EXECUTE.name())
                                .build()))
                .build();
        when(governanceSupport.getEffectivePolicy("ling1")).thenReturn(policy);

        coordinator.updateStatus("ling1", RuntimeStatus.INACTIVE, RuntimeStatus.ACTIVE, "1.0.0");

        // INACTIVE 路径必须走 recover，而非 transition
        verify(lifecycleEngine).recover("ling1", "1.0.0");
        verify(runtimeCoordinator, never()).transition(any(), any());
        // 已有 capabilities，不应再持久化补丁
        verify(governanceSupport, never()).persistPolicyPatch(any(), any());
        List<DashboardService.LifecycleEvent> events = eventStore.getEvents("ling1");
        assertEquals(1, events.size());
        assertEquals("ACTIVE", events.get(0).getType());
        assertEquals("1.0.0", events.get(0).getVersion());
    }

    @Test
    @DisplayName("从 INACTIVE 激活但 recover 抛异常时应抛 IllegalStateException 且不记录事件")
    void shouldThrowWhenRecoverFailsFromInactive() {
        // 提供 capabilities 满足激活前置校验，聚焦于 recover 异常路径
        GovernancePolicy policy = GovernancePolicy.builder()
                .capabilities(Arrays.asList(
                        GovernancePolicy.CapabilityRule.builder()
                                .capability(Capabilities.LING_ENABLE)
                                .accessType(AccessType.EXECUTE.name())
                                .build()))
                .build();
        when(governanceSupport.getEffectivePolicy("ling1")).thenReturn(policy);
        doThrow(new RuntimeException("boom")).when(lifecycleEngine).recover("ling1", "1.0.0");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> coordinator.updateStatus("ling1", RuntimeStatus.INACTIVE, RuntimeStatus.ACTIVE, "1.0.0"));

        assertTrue(ex.getMessage().contains("Cannot recover"));
        assertTrue(ex.getMessage().contains("ling1"));
        // recover 失败不应写入时间线
        assertTrue(eventStore.getEvents("ling1").isEmpty());
    }

    @Test
    @DisplayName("从 DEGRADED 激活应通过 runtimeCoordinator.transition 完成，而非 recover")
    void shouldTransitionWhenActivatingFromNonInactive() {
        GovernancePolicy policy = GovernancePolicy.builder()
                .capabilities(Arrays.asList(
                        GovernancePolicy.CapabilityRule.builder()
                                .capability(Capabilities.LING_ENABLE)
                                .accessType(AccessType.EXECUTE.name())
                                .build()))
                .build();
        when(governanceSupport.getEffectivePolicy("ling1")).thenReturn(policy);
        when(runtimeCoordinator.transition("ling1", RuntimeStatus.ACTIVE))
                .thenReturn(transitionSuccess());

        coordinator.updateStatus("ling1", RuntimeStatus.DEGRADED, RuntimeStatus.ACTIVE, "1.0.0");

        verify(runtimeCoordinator).transition("ling1", RuntimeStatus.ACTIVE);
        // 非 INACTIVE 路径不应调用 recover
        verify(lifecycleEngine, never()).recover(any(), any());
        List<DashboardService.LifecycleEvent> events = eventStore.getEvents("ling1");
        assertEquals(1, events.size());
        assertEquals("ACTIVE", events.get(0).getType());
    }

    @Test
    @DisplayName("从 DEGRADED 激活但 transition 失败时应抛 IllegalStateException")
    void shouldThrowWhenTransitionFailsFromNonInactive() {
        // 提供 capabilities 满足激活前置校验，聚焦于 transition 失败路径
        GovernancePolicy policy = GovernancePolicy.builder()
                .capabilities(Arrays.asList(
                        GovernancePolicy.CapabilityRule.builder()
                                .capability(Capabilities.LING_ENABLE)
                                .accessType(AccessType.EXECUTE.name())
                                .build()))
                .build();
        when(governanceSupport.getEffectivePolicy("ling1")).thenReturn(policy);
        when(runtimeCoordinator.transition("ling1", RuntimeStatus.ACTIVE))
                .thenReturn(transitionFailure());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> coordinator.updateStatus("ling1", RuntimeStatus.DEGRADED, RuntimeStatus.ACTIVE, "1.0.0"));

        assertTrue(ex.getMessage().contains("Cannot transition"));
        assertTrue(ex.getMessage().contains("DEGRADED"));
        // 失败不应写入时间线或撤销权限
        assertTrue(eventStore.getEvents("ling1").isEmpty());
        verify(permissionService, never()).revoke(any(), any());
    }

    @Test
    @DisplayName("激活时 effectivePolicy 为 null 应拒绝激活（不再自动注入默认 capabilities）")
    void shouldRejectActivationWhenEffectivePolicyIsNull() {
        when(governanceSupport.getEffectivePolicy("ling1")).thenReturn(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> coordinator.updateStatus("ling1", RuntimeStatus.INACTIVE, RuntimeStatus.ACTIVE, "1.0.0"));

        assertTrue(ex.getMessage().contains("Cannot activate"));
        assertTrue(ex.getMessage().contains("no capabilities configured"));
        // 激活被拒绝时不应触发恢复编排或权限补丁持久化
        verify(lifecycleEngine, never()).recover(any(), any());
        verify(governanceSupport, never()).persistPolicyPatch(any(), any());
        assertTrue(eventStore.getEvents("ling1").isEmpty());
    }

    @Test
    @DisplayName("激活时 capabilities 为空集合应拒绝激活")
    void shouldRejectActivationWhenCapabilitiesIsEmpty() {
        GovernancePolicy policy = GovernancePolicy.builder()
                .capabilities(Collections.emptyList())
                .build();
        when(governanceSupport.getEffectivePolicy("ling1")).thenReturn(policy);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> coordinator.updateStatus("ling1", RuntimeStatus.INACTIVE, RuntimeStatus.ACTIVE, "1.0.0"));

        assertTrue(ex.getMessage().contains("no capabilities configured"));
        verify(lifecycleEngine, never()).recover(any(), any());
        assertTrue(eventStore.getEvents("ling1").isEmpty());
    }

    // ==================== deactivateLing ====================

    @Test
    @DisplayName("transition INACTIVE 失败时仍应撤销 LING_ENABLE 但不写 INACTIVE 事件（C7）")
    void shouldRevokeEvenWhenDeactivateTransitionFails() {
        when(runtimeCoordinator.transition("ling1", RuntimeStatus.INACTIVE))
                .thenReturn(transitionFailure());

        // 不抛异常：核心是 revoke；不把 Runtime 当切流开关
        coordinator.updateStatus("ling1", RuntimeStatus.ACTIVE, RuntimeStatus.INACTIVE, "1.0.0");

        verify(permissionService).revoke("ling1", Capabilities.LING_ENABLE);
        verify(lifecycleEngine, never()).undeploy("ling1");
        // transition 未成功不得写 INACTIVE 事件（时间线只记录真实发生的事实）
        assertTrue(eventStore.getEvents("ling1").isEmpty());
    }

    @Test
    @DisplayName("收回 LING_ENABLE 成功并记录事件（不卸载）")
    void shouldRevokeEnableWhenDeactivated() {
        when(runtimeCoordinator.transition("ling1", RuntimeStatus.INACTIVE))
                .thenReturn(transitionSuccess());

        coordinator.updateStatus("ling1", RuntimeStatus.ACTIVE, RuntimeStatus.INACTIVE, "1.0.0");

        verify(permissionService).revoke("ling1", Capabilities.LING_ENABLE);
        verify(runtimeCoordinator).transition("ling1", RuntimeStatus.INACTIVE);
        verify(lifecycleEngine, never()).undeploy("ling1");
        List<DashboardService.LifecycleEvent> events = eventStore.getEvents("ling1");
        assertEquals(1, events.size());
        assertEquals("INACTIVE", events.get(0).getType());
        assertEquals("1.0.0", events.get(0).getVersion());
        assertTrue(events.get(0).getTitle().contains("启用权限")
                || events.get(0).getDescription().contains("LING_ENABLE"));
    }

    // ==================== recoverLing version 分支 ====================

    @Test
    @DisplayName("recover 传入 null version 时事件描述不应包含版本号")
    void shouldRecordRecoverEventsWithoutVersion() {
        coordinator.updateStatus("ling1", RuntimeStatus.DEGRADED, RuntimeStatus.RECOVERING, null);

        verify(lifecycleEngine).recover("ling1", null);
        List<DashboardService.LifecycleEvent> events = eventStore.getEvents("ling1");
        assertEquals(2, events.size());
        assertEquals("RECOVERING", events.get(0).getType());
        assertEquals("ACTIVE", events.get(1).getType());
        // version 为 null 时事件本身 version 字段也为 null
        assertNull(events.get(0).getVersion());
        assertNull(events.get(1).getVersion());
        // 描述走“不含版本”分支，不应出现“版本”字样
        assertFalse(events.get(0).getDescription().contains("版本"));
        assertFalse(events.get(1).getDescription().contains("版本"));
        // 描述仍应包含 lingId
        assertTrue(events.get(0).getDescription().contains("ling1"));
        assertTrue(events.get(1).getDescription().contains("ling1"));
    }

    @Test
    @DisplayName("recover 传入非 null version 时事件描述应包含版本号")
    void shouldRecordRecoverEventsWithVersion() {
        coordinator.updateStatus("ling1", RuntimeStatus.DEGRADED, RuntimeStatus.RECOVERING, "1.0.0");

        verify(lifecycleEngine).recover("ling1", "1.0.0");
        List<DashboardService.LifecycleEvent> events = eventStore.getEvents("ling1");
        assertEquals(2, events.size());
        // 两条事件描述都应包含具体版本号
        assertTrue(events.get(0).getDescription().contains("1.0.0"));
        assertTrue(events.get(1).getDescription().contains("1.0.0"));
        assertEquals("1.0.0", events.get(0).getVersion());
    }

    // ==================== 委托方法 ====================

    @Test
    @DisplayName("getRuntimeStatus 应委托给 runtimeCoordinator.getStatus")
    void getRuntimeStatusShouldDelegate() {
        when(runtimeCoordinator.getStatus("ling1")).thenReturn(RuntimeStatus.ACTIVE);

        RuntimeStatus status = coordinator.getRuntimeStatus("ling1");

        assertEquals(RuntimeStatus.ACTIVE, status);
        verify(runtimeCoordinator).getStatus("ling1");
    }

    @Test
    @DisplayName("getTransitionHistory 应委托给 runtimeCoordinator.getTransitionHistory")
    void getTransitionHistoryShouldDelegate() {
        List<TransitionRecord<RuntimeStatus>> records = Collections.emptyList();
        when(runtimeCoordinator.getTransitionHistory("ling1")).thenReturn(records);

        List<TransitionRecord<RuntimeStatus>> result = coordinator.getTransitionHistory("ling1");

        assertSame(records, result);
        verify(runtimeCoordinator).getTransitionHistory("ling1");
    }

    // ==================== 工具方法 ====================

    /**
     * 通过反射构造成功的 {@link TransitionResult}，避免依赖 final 类的 Mock 能力。
     */
    @SuppressWarnings("unchecked")
    private static TransitionResult<RuntimeStatus> transitionSuccess() {
        return invokeFactory("success", RuntimeStatus.DEGRADED, RuntimeStatus.ACTIVE);
    }

    /**
     * 通过反射构造失败的 {@link TransitionResult}（code=ILLEGAL）。
     */
    @SuppressWarnings("unchecked")
    private static TransitionResult<RuntimeStatus> transitionFailure() {
        return invokeFactory("illegal", RuntimeStatus.STOPPING, RuntimeStatus.ACTIVE);
    }

    /**
     * 调用 {@link TransitionResult} 的包级静态工厂方法。这些方法签名为 {@code (S, S)}，
     * 反擦除后参数类型为 {@code Enum}，这里按方法名 + 参数个数定位，规避擦除歧义。
     */
    @SuppressWarnings("unchecked")
    private static TransitionResult<RuntimeStatus> invokeFactory(String name, RuntimeStatus from, RuntimeStatus to) {
        try {
            Method factory = null;
            for (Method m : TransitionResult.class.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 2) {
                    factory = m;
                    break;
                }
            }
            if (factory == null) {
                throw new NoSuchMethodException("TransitionResult." + name);
            }
            factory.setAccessible(true);
            return (TransitionResult<RuntimeStatus>) factory.invoke(null, from, to);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("调用 TransitionResult 工厂方法失败: " + name, e);
        }
    }
}
