package com.lingframe.core.ling;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.RuntimeStateChangedEvent;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("LingRuntime 状态事件测试")
class LingRuntimeStateEventTest {

    @Nested
    @DisplayName("运行时状态广播")
    class RuntimeStateEventTests {

        @Test
        @DisplayName("停止事件应触发实例池清空")
        void shouldShutdownPoolOnStoppingState() {
            EventBus eventBus = new EventBus();
            LingRuntime runtime = new LingRuntime("ling-a", LingRuntimeConfig.defaults(), eventBus, new InstanceCoordinator(eventBus),
                    new RuntimeCoordinator(eventBus));

            eventBus.publish(new RuntimeStateChangedEvent("ling-a",
                    RuntimeStatus.ACTIVE,
                    RuntimeStatus.STOPPING));

            LingInstance instance = mock(LingInstance.class);
            when(instance.getVersion()).thenReturn("v1");
            runtime.getInstancePool().addInstance(instance, false);

            assertEquals(0, runtime.getInstancePool().getActiveInstances().size());
        }

        @Test
        @DisplayName("其他灵元的状态事件不应影响当前实例池")
        void shouldIgnoreOtherLingState() {
            EventBus eventBus = new EventBus();
            LingRuntime runtime = new LingRuntime("ling-a", LingRuntimeConfig.defaults(), eventBus, new InstanceCoordinator(eventBus),
                    new RuntimeCoordinator(eventBus));

            eventBus.publish(new RuntimeStateChangedEvent("ling-b",
                    RuntimeStatus.ACTIVE,
                    RuntimeStatus.STOPPING));

            LingInstance instance = mock(LingInstance.class);
            when(instance.getVersion()).thenReturn("v1");
            runtime.getInstancePool().addInstance(instance, false);

            assertEquals(1, runtime.getInstancePool().getActiveInstances().size());
        }
    }
}
