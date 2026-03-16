package com.lingframe.core.ling;

import com.lingframe.core.event.RuntimeStateChangedEvent;
import com.lingframe.core.event.EventBus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LingRuntimeStateEventTest {

    @Test
    void shouldShutdownPoolOnStoppingState() {
        EventBus eventBus = new EventBus();
        LingRuntime runtime = new LingRuntime("ling-a", LingRuntimeConfig.defaults(), eventBus);

        eventBus.publish(new RuntimeStateChangedEvent("ling-a",
                com.lingframe.core.fsm.RuntimeStatus.ACTIVE,
                com.lingframe.core.fsm.RuntimeStatus.STOPPING));

        LingInstance instance = mock(LingInstance.class);
        when(instance.getVersion()).thenReturn("v1");
        runtime.getInstancePool().addInstance(instance, false);

        assertEquals(0, runtime.getInstancePool().getActiveInstances().size());
    }

    @Test
    void shouldIgnoreOtherLingState() {
        EventBus eventBus = new EventBus();
        LingRuntime runtime = new LingRuntime("ling-a", LingRuntimeConfig.defaults(), eventBus);

        eventBus.publish(new RuntimeStateChangedEvent("ling-b",
                com.lingframe.core.fsm.RuntimeStatus.ACTIVE,
                com.lingframe.core.fsm.RuntimeStatus.STOPPING));

        LingInstance instance = mock(LingInstance.class);
        when(instance.getVersion()).thenReturn("v1");
        runtime.getInstancePool().addInstance(instance, false);

        assertEquals(1, runtime.getInstancePool().getActiveInstances().size());
    }
}
