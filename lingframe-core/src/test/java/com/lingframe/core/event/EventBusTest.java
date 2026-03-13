package com.lingframe.core.event;

import com.lingframe.api.event.LingEvent;
import com.lingframe.api.event.LingEventListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("EventBus tests")
public class EventBusTest {

    static class TestEvent implements LingEvent {
    }

    @Test
    @DisplayName("unsubscribe should remove only the specified listener")
    void unsubscribeShouldRemoveOnlySpecifiedListener() {
        EventBus eventBus = new EventBus();
        AtomicInteger first = new AtomicInteger(0);
        AtomicInteger second = new AtomicInteger(0);

        LingEventListener<TestEvent> firstListener = e -> first.incrementAndGet();
        LingEventListener<TestEvent> secondListener = e -> second.incrementAndGet();

        eventBus.subscribe("ling-a", TestEvent.class, firstListener);
        eventBus.subscribe("ling-a", TestEvent.class, secondListener);

        eventBus.unsubscribe("ling-a", TestEvent.class, firstListener);
        eventBus.publish(new TestEvent());

        assertEquals(0, first.get());
        assertEquals(1, second.get());
    }

    @Test
    @DisplayName("unsubscribe should not remove listeners with different lingId")
    void unsubscribeShouldRespectLingId() {
        EventBus eventBus = new EventBus();
        AtomicInteger count = new AtomicInteger(0);

        LingEventListener<TestEvent> listener = e -> count.incrementAndGet();

        eventBus.subscribe("ling-a", TestEvent.class, listener);
        eventBus.unsubscribe("ling-b", TestEvent.class, listener);
        eventBus.publish(new TestEvent());

        assertEquals(1, count.get());
    }

    @Test
    @DisplayName("unsubscribeAll should remove all listeners for target lingId only")
    void unsubscribeAllShouldRemoveAllForLingId() {
        EventBus eventBus = new EventBus();
        AtomicInteger a1 = new AtomicInteger(0);
        AtomicInteger a2 = new AtomicInteger(0);
        AtomicInteger b = new AtomicInteger(0);

        LingEventListener<TestEvent> listenerA1 = e -> a1.incrementAndGet();
        LingEventListener<TestEvent> listenerA2 = e -> a2.incrementAndGet();
        LingEventListener<TestEvent> listenerB = e -> b.incrementAndGet();

        eventBus.subscribe("ling-a", TestEvent.class, listenerA1);
        eventBus.subscribe("ling-a", TestEvent.class, listenerA2);
        eventBus.subscribe("ling-b", TestEvent.class, listenerB);

        eventBus.unsubscribeAll("ling-a");
        eventBus.publish(new TestEvent());

        assertEquals(0, a1.get());
        assertEquals(0, a2.get());
        assertEquals(1, b.get());
    }
}
