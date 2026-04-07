package com.lingframe.core.event;

import com.lingframe.api.event.LingEvent;
import com.lingframe.api.event.LingEventListener;
import com.lingframe.core.event.monitor.MonitoringEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EventBus 测试")
public class EventBusTest {

    static class TestEvent implements LingEvent {
    }

    @Test
    @DisplayName("取消订阅时应只移除指定监听器")
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
    @DisplayName("取消订阅时不应误删其他灵元的监听器")
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
    @DisplayName("批量取消订阅时应只移除目标灵元的全部监听器")
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

    @Test
    @DisplayName("监控事件应异步分发，不阻塞发布线程")
    void publishShouldDispatchMonitoringEventsAsynchronously() throws Exception {
        EventBus eventBus = new EventBus(1, 8);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        eventBus.subscribe("ling-a", MonitoringEvents.TraceLogEvent.class, event -> {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        long start = System.nanoTime();
        eventBus.publish(new MonitoringEvents.TraceLogEvent("trace-1", "ling-a", "action", "INFO", 1));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        try {
            assertTrue(elapsedMs < 200, "publish should not block on async monitoring listeners");
            assertTrue(entered.await(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            eventBus.shutdown();
        }
    }

    @Test
    @DisplayName("异步观测队列满时应记录丢弃计数")
    void publishShouldCountDroppedAsyncEventsWhenQueueIsFull() throws Exception {
        EventBus eventBus = new EventBus(1, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        eventBus.subscribe("ling-a", MonitoringEvents.TraceLogEvent.class, event -> {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            eventBus.publish(new MonitoringEvents.TraceLogEvent("trace-1", "ling-a", "action", "INFO", 1));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            eventBus.publish(new MonitoringEvents.TraceLogEvent("trace-2", "ling-a", "action", "INFO", 1));
            eventBus.publish(new MonitoringEvents.TraceLogEvent("trace-3", "ling-a", "action", "INFO", 1));

            assertTrue(eventBus.getDroppedAsyncEvents() > 0);
        } finally {
            release.countDown();
            eventBus.shutdown();
        }
    }
}
