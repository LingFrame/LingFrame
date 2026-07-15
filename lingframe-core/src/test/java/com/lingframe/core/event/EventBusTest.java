package com.lingframe.core.event;

import com.lingframe.api.event.LingEvent;
import com.lingframe.api.event.LingEventListener;
import com.lingframe.core.event.monitor.MonitoringEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ==================== unsubscribe 原子性（P2-3）====================

    @Nested
    @DisplayName("unsubscribe 原子性")
    class UnsubscribeAtomicity {

        @Test
        @DisplayName("unsubscribeGlobal 只移除指定的全局监听器")
        void unsubscribeGlobalRemovesOnlySpecified() {
            EventBus eventBus = new EventBus();
            AtomicInteger first = new AtomicInteger(0);
            AtomicInteger second = new AtomicInteger(0);

            LingEventListener<TestEvent> firstListener = e -> first.incrementAndGet();
            LingEventListener<TestEvent> secondListener = e -> second.incrementAndGet();

            eventBus.subscribeGlobal(TestEvent.class, firstListener);
            eventBus.subscribeGlobal(TestEvent.class, secondListener);

            eventBus.unsubscribeGlobal(TestEvent.class, firstListener);
            eventBus.publish(new TestEvent());

            assertEquals(0, first.get());
            assertEquals(1, second.get());
            eventBus.shutdown();
        }

        @Test
        @DisplayName("全部取消订阅后再次订阅仍能收到事件")
        void resubscribeAfterAllUnsubscribed() {
            EventBus eventBus = new EventBus();
            AtomicInteger count = new AtomicInteger(0);
            LingEventListener<TestEvent> listener = e -> count.incrementAndGet();

            // 订阅后取消（列表清空，entry 移除）
            eventBus.subscribe("ling-a", TestEvent.class, listener);
            eventBus.unsubscribe("ling-a", TestEvent.class, listener);

            // 再次订阅，应能正常收到事件（验证 entry 被正确移除后可重建）
            eventBus.subscribe("ling-a", TestEvent.class, listener);
            eventBus.publish(new TestEvent());

            assertEquals(1, count.get());
            eventBus.shutdown();
        }

        @Test
        @DisplayName("全部取消全局订阅后再次订阅仍能收到事件")
        void resubscribeGlobalAfterAllUnsubscribed() {
            EventBus eventBus = new EventBus();
            AtomicInteger count = new AtomicInteger(0);
            LingEventListener<TestEvent> listener = e -> count.incrementAndGet();

            eventBus.subscribeGlobal(TestEvent.class, listener);
            eventBus.unsubscribeGlobal(TestEvent.class, listener);

            eventBus.subscribeGlobal(TestEvent.class, listener);
            eventBus.publish(new TestEvent());

            assertEquals(1, count.get());
            eventBus.shutdown();
        }

        @Test
        @DisplayName("并发取消订阅不丢失其他监听器")
        void concurrentUnsubscribeNoListenerLoss() throws Exception {
            EventBus eventBus = new EventBus();
            int listenerCount = 20;
            AtomicInteger[] counters = new AtomicInteger[listenerCount];
            LingEventListener<TestEvent>[] listeners = new LingEventListener[listenerCount];

            for (int i = 0; i < listenerCount; i++) {
                counters[i] = new AtomicInteger(0);
                final AtomicInteger counter = counters[i];
                listeners[i] = e -> counter.incrementAndGet();
                eventBus.subscribe("ling-a", TestEvent.class, listeners[i]);
            }

            // 并发取消前 10 个监听器
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        start.await();
                        eventBus.unsubscribe("ling-a", TestEvent.class, listeners[idx]);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            // 发布事件，后 10 个监听器应全部收到
            eventBus.publish(new TestEvent());

            // 前 10 个不应收到
            for (int i = 0; i < threadCount; i++) {
                assertEquals(0, counters[i].get(), "已取消订阅的监听器不应收到事件");
            }
            // 后 10 个应各收到 1 次
            for (int i = threadCount; i < listenerCount; i++) {
                assertEquals(1, counters[i].get(), "未取消订阅的监听器应收到事件");
            }
            eventBus.shutdown();
        }
    }
}
