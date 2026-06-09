package com.lingframe.core.event;

import com.lingframe.core.fsm.InstanceStatus;
import com.lingframe.core.fsm.RuntimeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Event 类测试")
class EventClassesTest {

    @Test
    @DisplayName("RuntimeStateChangedEvent 构造和 getter")
    void shouldCreateRuntimeStateChangedEvent() {
        RuntimeStateChangedEvent event = new RuntimeStateChangedEvent(
                "ling-a", RuntimeStatus.INACTIVE, RuntimeStatus.ACTIVE);

        assertEquals("ling-a", event.getLingId());
        assertEquals(RuntimeStatus.INACTIVE, event.getFrom());
        assertEquals(RuntimeStatus.ACTIVE, event.getTo());
        assertTrue(event.getTimestamp() > 0);
        assertTrue(event.toString().contains("ling-a"));
    }

    @Test
    @DisplayName("InstanceStateChangedEvent 构造和 getter")
    void shouldCreateInstanceStateChangedEvent() {
        InstanceStateChangedEvent event = new InstanceStateChangedEvent(
                "ling-b", "1.0.0", InstanceStatus.CREATED, InstanceStatus.READY);

        assertEquals("ling-b", event.getLingId());
        assertEquals("1.0.0", event.getVersion());
        assertEquals(InstanceStatus.CREATED, event.getFromStatus());
        assertEquals(InstanceStatus.READY, event.getToStatus());
        assertTrue(event.getTimestamp() > 0);
        assertTrue(event.toString().contains("ling-b"));
    }

    @Test
    @DisplayName("InstanceDestroyedEvent 构造和 getter")
    void shouldCreateInstanceDestroyedEvent() {
        InstanceDestroyedEvent event = new InstanceDestroyedEvent("ling-c", "2.0.0");

        assertEquals("ling-c", event.getLingId());
        assertEquals("2.0.0", event.getVersion());
        assertTrue(event.getTimestamp() > 0);
    }
}
