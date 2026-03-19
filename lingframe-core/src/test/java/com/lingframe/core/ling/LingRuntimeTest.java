package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.event.LingEventListener;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.RuntimeStateChangedEvent;
import com.lingframe.core.fsm.InstanceCoordinator;
import com.lingframe.core.fsm.InstanceStatus;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.spi.LingContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LingRuntime 灵元运行时测试")
class LingRuntimeTest {

    private static final String LING_ID = "test-ling";

    @Mock
    private EventBus eventBus;

    @Mock
    private InstanceCoordinator instanceCoordinator;

    private LingRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = new LingRuntime(LING_ID, LingRuntimeConfig.defaults(), eventBus, instanceCoordinator);
    }

    @Nested
    @DisplayName("基础状态测试")
    class BasicStateTests {

        @Test
        @DisplayName("应该有正确的初始值")
        void shouldHaveCorrectInitialValues() {
            assertEquals(LING_ID, runtime.getLingId());
            assertNotNull(runtime.getConfig());
            assertNotNull(runtime.getInstancePool());
            assertNotNull(runtime.getStateMachine());
            assertEquals(RuntimeStatus.INACTIVE, runtime.currentStatus());
            assertFalse(runtime.isAvailable());
        }

        @Test
        @DisplayName("应能获取就绪实例")
        void shouldGetReadyInstances() {
            assertTrue(runtime.getReadyInstances().isEmpty());

            LingInstance readyInstance = createMockInstance("1.0.0", true);
            runtime.getInstancePool().addInstance(readyInstance, true);

            List<LingInstance> ready = runtime.getReadyInstances();
            assertEquals(1, ready.size());
            assertEquals(readyInstance, ready.get(0));
        }

        @Test
        @DisplayName("可用性判定：需 ACTIVE 状态且池中有实例")
        void availabilityCheck() {
            assertFalse(runtime.isAvailable());

            runtime.getInstancePool().addInstance(createMockInstance("1.0.0", true), true);
            assertFalse(runtime.isAvailable());

            runtime.getStateMachine().transition(RuntimeStatus.ACTIVE);
            assertTrue(runtime.isAvailable());
        }
    }

    @Nested
    @DisplayName("流量统计测试")
    class TrafficStatsTests {

        @Test
        @DisplayName("应该准确记录请求数")
        void shouldRecordRequests() {
            runtime.recordRequest(false);
            runtime.recordRequest(false);
            runtime.recordRequest(true);

            assertEquals(3, runtime.getTotalRequests().get());
            assertEquals(2, runtime.getStableRequests().get());
            assertEquals(1, runtime.getCanaryRequests().get());
        }

        @Test
        @DisplayName("应该准确跟踪活跃请求")
        void shouldTrackActiveRequests() {
            runtime.startRequest();
            runtime.startRequest();
            assertEquals(2, runtime.getActiveRequests().get());

            runtime.endRequest();
            assertEquals(1, runtime.getActiveRequests().get());
        }
    }

    @Nested
    @DisplayName("事件驱动测试")
    class EventDrivenTests {

        @Test
        @DisplayName("收到卸载相关事件时应关闭实例池")
        @SuppressWarnings("unchecked")
        void shouldShutdownPoolOnUnloadEvent() {
            InstancePool pool = runtime.getInstancePool();
            
            // 正常状态，应能添加实例
            LingInstance inst1 = createMockInstance("1.0.1", true);
            pool.addInstance(inst1, false);
            assertTrue(pool.getActiveInstances().contains(inst1));

            // 捕获订阅的回调
            ArgumentCaptor<LingEventListener<RuntimeStateChangedEvent>> captor = ArgumentCaptor.forClass(LingEventListener.class);
            verify(eventBus).subscribe(eq(LING_ID), eq(RuntimeStateChangedEvent.class), captor.capture());
            
            LingEventListener<RuntimeStateChangedEvent> listener = captor.getValue();
            
            // 模拟发布状态变更事件到 REMOVED
            RuntimeStateChangedEvent event = new RuntimeStateChangedEvent(LING_ID, RuntimeStatus.ACTIVE, RuntimeStatus.REMOVED);
            listener.onEvent(event);

            // 行为验证：关停后应无法添加新实例 (addInstance 返回 null 且不加入 activePool)
            LingInstance inst2 = createMockInstance("1.0.2", true);
            LingInstance result = pool.addInstance(inst2, false);
            
            assertNull(result, "Shutting down pool should return null when adding instance");
            assertFalse(pool.getActiveInstances().contains(inst2), "Shutting down pool should not accept new instances");
            
            // 且之前的活跃实例应已被移至死亡队列（或清空，取决于具体的 shutdown 语义，这里验证活跃池为空）
            assertTrue(pool.getActiveInstances().isEmpty(), "Active pool should be cleared after shutdown");
        }
    }

    @Test
    @DisplayName("复杂构造场景：配置为 null 时应使用默认值")
    void shouldHandleNullConfig() {
        LingRuntime nullConfigRuntime = new LingRuntime("null-id", null, eventBus);
        assertNotNull(nullConfigRuntime.getConfig());
    }

    private LingInstance createMockInstance(String version, boolean ready) {
        LingContainer container = mock(LingContainer.class);
        lenient().when(container.isActive()).thenReturn(ready);
        lenient().when(container.getClassLoader()).thenReturn(this.getClass().getClassLoader());
        
        LingDefinition def = new LingDefinition();
        def.setId(LING_ID);
        def.setVersion(version);
        
        LingInstance instance = new LingInstance(container, def, eventBus);
        if (ready) {
            instance.getStateMachine().transition(InstanceStatus.LOADING);
            instance.getStateMachine().transition(InstanceStatus.STARTING);
            instance.markReady();
        }
        return instance;
    }
}