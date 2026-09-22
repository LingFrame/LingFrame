package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.InstanceDestroyedEvent;
import com.lingframe.core.event.InstanceStateChangedEvent;
import com.lingframe.core.exception.IllegalStateTransitionException;
import com.lingframe.core.fsm.InstanceStatus;
import com.lingframe.core.spi.LingContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * InstanceCoordinator 测试。
 * 覆盖：正常状态推进、非法转换异常、tearDown 全流程、事件发布。
 */
@DisplayName("InstanceCoordinator 测试")
class InstanceCoordinatorTest {

    private EventBus eventBus;
    private InstanceCoordinator coordinator;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        coordinator = new InstanceCoordinator(eventBus);
    }

    /**
     * 创建测试用 LingInstance（需要合法的 LingDefinition + LingContainer）
     */
    private LingInstance createInstance(String lingId, String version) {
        LingDefinition def = new LingDefinition();
        def.setId(lingId);
        def.setVersion(version);
        LingContainer container = mock(LingContainer.class);
        when(container.isActive()).thenReturn(true);
        return new LingInstance(container, def, eventBus);
    }

    // ==================== 正常状态推进 ====================

    @Nested
    @DisplayName("正常状态推进")
    class NormalTransition {

        @Test
        @DisplayName("prepare 驱动 CREATED → LOADING")
        void prepareDrivesLoading() {
            LingInstance instance = createInstance("ling-1", "v1");
            assertEquals(InstanceStatus.CREATED, instance.currentStatus());

            coordinator.prepare(instance);
            assertEquals(InstanceStatus.LOADING, instance.currentStatus());
        }

        @Test
        @DisplayName("完整生命周期 CREATED → LOADING → STARTING → READY")
        void fullLifecycleToReady() {
            LingInstance instance = createInstance("ling-1", "v1");

            coordinator.prepare(instance);
            assertEquals(InstanceStatus.LOADING, instance.currentStatus());

            coordinator.start(instance);
            assertEquals(InstanceStatus.STARTING, instance.currentStatus());

            coordinator.markReady(instance);
            assertEquals(InstanceStatus.READY, instance.currentStatus());
        }

        @Test
        @DisplayName("stop 驱动 READY → STOPPING")
        void stopDrivesStopping() {
            LingInstance instance = createInstance("ling-1", "v1");
            coordinator.prepare(instance);
            coordinator.start(instance);
            coordinator.markReady(instance);

            coordinator.stop(instance);
            assertEquals(InstanceStatus.STOPPING, instance.currentStatus());
        }

        @Test
        @DisplayName("error 驱动任意活跃态 → ERROR")
        void errorDrivesError() {
            LingInstance instance = createInstance("ling-1", "v1");
            coordinator.prepare(instance);
            coordinator.start(instance);

            coordinator.error(instance);
            assertEquals(InstanceStatus.ERROR, instance.currentStatus());
        }

        @Test
        @DisplayName("recovering 驱动 ERROR → RECOVERING")
        void recoveringFromError() {
            LingInstance instance = createInstance("ling-1", "v1");
            coordinator.prepare(instance);
            coordinator.error(instance);
            assertEquals(InstanceStatus.ERROR, instance.currentStatus());

            coordinator.recovering(instance);
            assertEquals(InstanceStatus.RECOVERING, instance.currentStatus());
        }
    }

    // ==================== 非法转换 ====================

    @Nested
    @DisplayName("非法转换异常")
    class IllegalTransition {

        @Test
        @DisplayName("CREATED → READY 非法，抛出 IllegalStateTransitionException")
        void createdToReadyIllegal() {
            LingInstance instance = createInstance("ling-1", "v1");
            assertThrows(IllegalStateTransitionException.class,
                    () -> coordinator.markReady(instance));
        }

        @Test
        @DisplayName("DEAD 是终态，不可转换")
        void deadIsTerminal() {
            LingInstance instance = createInstance("ling-1", "v1");
            coordinator.prepare(instance);
            coordinator.start(instance);
            coordinator.markReady(instance);
            coordinator.stop(instance);
            // 手动驱动到 DEAD
            instance.transitionState(InstanceStatus.DEAD);

            assertThrows(IllegalStateTransitionException.class,
                    () -> coordinator.prepare(instance));
        }
    }

    // ==================== 事件发布 ====================

    @Nested
    @DisplayName("状态变更事件发布")
    class EventPublishing {

        @Test
        @DisplayName("状态转换成功后发布 InstanceStateChangedEvent")
        void publishesStateChangedEvent() {
            AtomicReference<InstanceStateChangedEvent> captured = new AtomicReference<>();
            eventBus.subscribeGlobal(InstanceStateChangedEvent.class, captured::set);

            LingInstance instance = createInstance("ling-1", "v1");
            coordinator.prepare(instance);

            awaitOrFail(captured);
            InstanceStateChangedEvent event = captured.get();
            assertNotNull(event);
            assertEquals("ling-1", event.getLingId());
            assertEquals("v1", event.getVersion());
            assertEquals(InstanceStatus.CREATED, event.getFromStatus());
            assertEquals(InstanceStatus.LOADING, event.getToStatus());
        }
    }

    // ==================== tearDown ====================

    @Nested
    @DisplayName("tearDown 全流程")
    class TearDown {

        @Test
        @DisplayName("tearDown 从 READY 驱动到 DEAD")
        void tearDownFromReady() {
            LingInstance instance = createInstance("ling-1", "v1");
            coordinator.prepare(instance);
            coordinator.start(instance);
            coordinator.markReady(instance);
            assertEquals(InstanceStatus.READY, instance.currentStatus());

            coordinator.tearDown(instance);
            assertEquals(InstanceStatus.DEAD, instance.currentStatus());
        }

        @Test
        @DisplayName("tearDown 从 ERROR 驱动到 DEAD")
        void tearDownFromError() {
            LingInstance instance = createInstance("ling-1", "v1");
            coordinator.prepare(instance);
            coordinator.error(instance);
            assertEquals(InstanceStatus.ERROR, instance.currentStatus());

            coordinator.tearDown(instance);
            assertEquals(InstanceStatus.DEAD, instance.currentStatus());
        }

        @Test
        @DisplayName("tearDown 已 DEAD 实例幂等")
        void tearDownAlreadyDead() {
            LingInstance instance = createInstance("ling-1", "v1");
            coordinator.prepare(instance);
            coordinator.start(instance);
            coordinator.markReady(instance);
            coordinator.stop(instance);
            instance.transitionState(InstanceStatus.DEAD);

            // 不应抛异常
            assertDoesNotThrow(() -> coordinator.tearDown(instance));
        }

        @Test
        @DisplayName("tearDown 调用 terminator 终止容器")
        void tearDownCallsTerminator() {
            LingContainer container = mock(LingContainer.class);
            when(container.isActive()).thenReturn(true);

            LingDefinition def = new LingDefinition();
            def.setId("ling-1");
            def.setVersion("v1");
            LingInstance instance = new LingInstance(container, def, eventBus);

            coordinator.prepare(instance);
            coordinator.start(instance);
            coordinator.markReady(instance);

            coordinator.tearDown(instance);

            verify(container).stop();
        }
    }

    // ==================== 自定义 Terminator ====================

    @Nested
    @DisplayName("自定义 Terminator")
    class CustomTerminator {

        @Test
        @DisplayName("注入自定义 LingInstanceTerminator")
        void customTerminatorUsed() {
            LingInstanceTerminator customTerminator = mock(LingInstanceTerminator.class);
            InstanceCoordinator coord = new InstanceCoordinator(eventBus, customTerminator);

            LingInstance instance = createInstance("ling-1", "v1");
            coord.prepare(instance);
            coord.start(instance);
            coord.markReady(instance);

            coord.tearDown(instance);

            verify(customTerminator).terminate(instance);
        }

        @Test
        @DisplayName("terminate 失败时实例进入 ERROR 且仍发布 InstanceDestroyedEvent（快照收敛）")
        void terminateFailurePublishesDestroyedEvent() {
            LingInstanceTerminator failingTerminator = mock(LingInstanceTerminator.class);
            doThrow(new RuntimeException("resource cleanup failed")).when(failingTerminator).terminate(any());
            InstanceCoordinator coord = new InstanceCoordinator(eventBus, failingTerminator);

            AtomicReference<InstanceDestroyedEvent> destroyed = new AtomicReference<>();
            eventBus.subscribeGlobal(InstanceDestroyedEvent.class, destroyed::set);

            LingInstance instance = createInstance("ling-1", "v1");
            coord.prepare(instance);
            coord.start(instance);
            coord.markReady(instance);

            assertDoesNotThrow(() -> coord.tearDown(instance));
            assertEquals(InstanceStatus.ERROR, instance.currentStatus(),
                    "terminate 失败后实例应进入 ERROR 而非 DEAD");

            awaitOrFail(destroyed);
            assertNotNull(destroyed.get(), "tearDown 失败后仍应补发 InstanceDestroyedEvent，避免 RuntimeCoordinator 快照残留");
            assertEquals("ling-1", destroyed.get().getLingId());
            assertEquals("v1", destroyed.get().getVersion());
        }
    }

    // ==================== null EventBus ====================

    @Nested
    @DisplayName("null EventBus 安全")
    class NullEventBus {

        @Test
        @DisplayName("eventBus 为 null 时不发布事件，不抛异常")
        void nullEventBusSafe() {
            InstanceCoordinator coord = new InstanceCoordinator(null);
            LingInstance instance = createInstance("ling-1", "v1");

            assertDoesNotThrow(() -> coord.prepare(instance));
            assertEquals(InstanceStatus.LOADING, instance.currentStatus());
        }
    }

    // ==================== 辅助方法 ====================

    private void awaitOrFail(AtomicReference<?> ref) {
        long deadline = System.currentTimeMillis() + 2000;
        while (ref.get() == null && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        assertNotNull(ref.get(), "Timeout waiting for async event");
    }
}
