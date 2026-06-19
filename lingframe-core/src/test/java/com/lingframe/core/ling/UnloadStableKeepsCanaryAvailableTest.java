package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.InstanceStatus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.spi.LingContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用真实组件组合（EventBus + RuntimeCoordinator + InstancePool + InstanceCoordinator）
 * 精确复现 {@code DefaultLingLifecycleEngine.undeploySelectedInstance} 的 4 步流程，
 * 定位「卸载稳定版后金丝雀是否仍可访问」的真实答案。
 * <p>
 * 不构造完整引擎（避免 MetricsCollector/AlertManager 等后台线程导致 forked VM 崩溃），
 * 而是手动按引擎的真实调用顺序驱动，覆盖 moveToDying → (drain) → tearDown → removeInstance。
 */
@DisplayName("卸载稳定版后金丝雀可用性回归测试")
class UnloadStableKeepsCanaryAvailableTest {

    private EventBus eventBus;
    private RuntimeCoordinator runtimeCoordinator;
    private InstanceCoordinator instanceCoordinator;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();
        instanceCoordinator = new InstanceCoordinator(eventBus);
    }

    @AfterEach
    void tearDown() {
        runtimeCoordinator.stop();
    }

    @Test
    @DisplayName("卸载稳定版后_金丝雀仍可访问且运行时保持ACTIVE")
    void canaryRemainsAvailableAfterStableUnloaded() {
        LingRuntime runtime = new LingRuntime(
                "ling-a",
                LingRuntimeConfig.builder().forceCleanupDelaySeconds(0).build(),
                eventBus,
                instanceCoordinator,
                runtimeCoordinator);

        LingInstance stable = newReadyInstance("ling-a", "1.0.0", false);
        LingInstance canary = newReadyInstance("ling-a", "2.0.0", true);

        runtime.getInstancePool().addInstance(stable, true);
        runtime.getInstancePool().addInstance(canary, false);

        // 基线：双版本就绪
        assertEquals(RuntimeStatus.ACTIVE, runtime.currentStatus(),
                "双版本就绪后运行时应为 ACTIVE");
        assertTrue(runtime.isAvailable(), "双版本就绪时灵元应可访问");
        assertEquals(2, runtime.getReadyInstances().size(), "两个实例都应可路由");

        // ===== 模拟 DefaultLingLifecycleEngine.undeploySelectedInstance 的真实顺序 =====
        // moveToDying(stable) -> (drainInstances 跳过，无活跃调用) -> tearDown -> removeInstance
        runtime.getInstancePool().moveToDying(stable);
        instanceCoordinator.tearDown(stable);
        runtime.getInstancePool().removeInstance(stable);

        // ===== 关键断言 =====
        RuntimeStatus statusAfter = runtime.currentStatus();
        assertTrue(statusAfter == RuntimeStatus.ACTIVE || statusAfter == RuntimeStatus.DEGRADED,
                "卸载稳定版后金丝雀仍在，运行时应保持 ACTIVE/DEGRADED，实际为 " + statusAfter);

        assertTrue(runtime.isAvailable(),
                "卸载稳定版后 isAvailable()=false 会导致灵元对所有调用不可访问；status=" + statusAfter);

        assertFalse(runtime.getReadyInstances().isEmpty(),
                "卸载稳定版后无 READY 实例可路由");

        assertSame(canary, runtime.getInstancePool().getDefault(),
                "卸载稳定版后金丝雀应被选举为新默认实例");

        assertEquals(InstanceStatus.READY, canary.currentStatus(),
                "卸载稳定版不应影响金丝雀实例状态");
    }

    @Test
    @DisplayName("金丝雀作为唯一实例时_canary标记不影响其可访问性")
    void singleCanaryInstanceRemainsAvailable() {
        // 验证金丝雀被选举为默认后，即使 canary 标记仍在，
        // 它作为唯一可用实例时灵元仍可访问（路由层不会因标记而拒服务）。
        LingRuntime runtime = new LingRuntime(
                "ling-a",
                LingRuntimeConfig.defaults(),
                eventBus,
                instanceCoordinator,
                runtimeCoordinator);

        LingInstance canary = newReadyInstance("ling-a", "2.0.0", true);
        runtime.getInstancePool().addInstance(canary, true);

        assertTrue(runtime.isAvailable(), "金丝雀作为唯一实例时应可访问");
        assertEquals(1, runtime.getReadyInstances().size());
        assertSame(canary, runtime.getInstancePool().getDefault());
    }

    private LingInstance newReadyInstance(String lingId, String version, boolean canary) {
        LingContainer container = mock(LingContainer.class);
        when(container.isActive()).thenReturn(true);
        when(container.getClassLoader()).thenReturn(getClass().getClassLoader());

        LingDefinition definition = new LingDefinition();
        definition.setId(lingId);
        definition.setVersion(version);
        definition.setMainClass("demo.Main");
        if (canary) {
            definition.getProperties().put("canary", true);
        }

        LingInstance instance = new LingInstance(container, definition, eventBus);
        instanceCoordinator.prepare(instance);
        instanceCoordinator.start(instance);
        instanceCoordinator.markReady(instance);
        return instance;
    }
}
