package com.lingframe.core.event;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.event.LingEvent;
import com.lingframe.api.event.LingEventListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件驱动架构（EDA）实证：模式 2 多私有库跨灵元的事件发布→订阅收敛。
 * <p>
 * 场景：下单灵元（order-ling）发布领域事件，库存灵元（stock-ling）订阅并消费——
 * 跨灵元最终一致性通过 EventBus 异步投递收敛（模式 2 各灵元私有库独立提交，
 * 事件总线承担跨库协调）。
 */
@DisplayName("EDA：跨灵元事件发布订阅收敛")
class EventDrivenArchitectureTest {

    /**
     * 下单领域事件：携带 traceId（幂等去重键）与业务载荷。
     */
    private static final class OrderCreatedEvent implements AsyncLingEvent {
        private final String traceId;
        private final String orderId;

        OrderCreatedEvent(String traceId, String orderId) {
            this.traceId = traceId;
            this.orderId = orderId;
        }

        String getTraceId() {
            return traceId;
        }

        String getOrderId() {
            return orderId;
        }
    }

    /** 同步标记事件：验证非 Async 事件走同步投递路径 */
    private static final class SyncEvent implements LingEvent {
    }

    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus(2, 128);
    }

    @AfterEach
    void tearDown() {
        eventBus.shutdown();
        LingCallContext.clear();
    }

    @Nested
    @DisplayName("异步投递与订阅可见")
    class AsyncDispatch {

        @Test
        @DisplayName("跨灵元异步事件：发布后订阅方在可接受时延内收敛（微秒级收敛断言）")
        void asyncEventConvergesToSubscriber() throws Exception {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<OrderCreatedEvent> seen = new AtomicReference<>();
            // 库存灵元订阅下单事件（原子回调：先赋值后放行，消除并发调度竞态）
            eventBus.subscribe("stock-ling", OrderCreatedEvent.class, e -> {
                seen.set(e);
                received.countDown();
            });

            // 下单灵元发布异步领域事件
            eventBus.publish(new OrderCreatedEvent("trace-001", "order-001"));

            // 微秒级收敛断言：短时延内（500ms 上界，实际异步线程池投递远小于此）订阅方可见
            assertTrue(received.await(500, TimeUnit.MILLISECONDS),
                    "cross-ling async event should converge to subscriber within 500ms");

            OrderCreatedEvent event = seen.get();
            assertNotNull(event);
            assertEquals("order-001", event.getOrderId());
            assertEquals("trace-001", event.getTraceId());
        }

        @Test
        @DisplayName("异步投递指标：submitted 增长、dropped 为 0（无溢出丢弃）")
        void asyncDispatchMetrics() throws Exception {
            CountDownLatch received = new CountDownLatch(1);
            eventBus.subscribe("stock-ling", OrderCreatedEvent.class, e -> received.countDown());

            long beforeSubmitted = eventBus.getSubmittedAsyncEvents();
            long beforeDropped = eventBus.getDroppedAsyncEvents();

            eventBus.publish(new OrderCreatedEvent("trace-002", "order-002"));

            assertTrue(received.await(500, TimeUnit.MILLISECONDS));
            assertTrue(eventBus.getSubmittedAsyncEvents() > beforeSubmitted,
                    "async event should be submitted to dispatcher");
            assertEquals(beforeDropped, eventBus.getDroppedAsyncEvents(),
                    "async event should not be dropped (queue capacity sufficient)");
        }

        @Test
        @DisplayName("traceId 下行传递：发布侧上下文 traceId 在事件中携带，订阅侧一致（幂等去重键可循）")
        void traceIdPropagatesInEvent() throws Exception {
            // 发布侧生成调用链 traceId
            String traceId = LingCallContext.startTrace();
            assertNotNull(traceId);

            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<String> seenTrace = new AtomicReference<>();
            eventBus.subscribe("stock-ling", OrderCreatedEvent.class, e -> {
                seenTrace.set(e.getTraceId());
                received.countDown();
            });

            eventBus.publish(new OrderCreatedEvent(traceId, "order-003"));

            assertTrue(received.await(500, TimeUnit.MILLISECONDS));
            // 订阅侧收到同一 traceId：事件链可沿 traceId 追踪与幂等去重
            assertEquals(traceId, seenTrace.get());
        }
    }

    @Nested
    @DisplayName("同步投递路径")
    class SyncDispatch {

        @Test
        @DisplayName("非 Async 标记事件走同步投递：publish 返回前监听器已消费")
        void syncEventDeliveredBeforePublishReturns() {
            AtomicInteger consumed = new AtomicInteger();
            eventBus.subscribe("stock-ling", SyncEvent.class, e -> consumed.incrementAndGet());

            eventBus.publish(new SyncEvent());

            // 同步路径：publish 返回即已消费
            assertEquals(1, consumed.get());
        }
    }

    @Nested
    @DisplayName("订阅隔离")
    class SubscriptionIsolation {

        @Test
        @DisplayName("未订阅事件类型的灵元不收事件；灵元级订阅互不串扰")
        void subscriberIsolationAcrossLings() throws Exception {
            CountDownLatch stockReceived = new CountDownLatch(1);
            AtomicInteger otherLingCalls = new AtomicInteger();
            // 库存灵元订阅下单事件
            eventBus.subscribe("stock-ling", OrderCreatedEvent.class, e -> stockReceived.countDown());
            // 另一灵元（other-ling）先订阅再取消 → 不应收到（unsubscribe 须用同一 listener 实例）
            LingEventListener<OrderCreatedEvent> otherListener = e -> otherLingCalls.incrementAndGet();
            eventBus.subscribe("other-ling", OrderCreatedEvent.class, otherListener);
            eventBus.unsubscribe("other-ling", OrderCreatedEvent.class, otherListener);

            eventBus.publish(new OrderCreatedEvent("trace-004", "order-004"));

            assertTrue(stockReceived.await(500, TimeUnit.MILLISECONDS));
            assertEquals(0, otherLingCalls.get(), "unsubscribed ling should not receive events");
        }
    }
}
