package com.lingframe.core.ling.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RuntimeEventBus 单元测试")
public class RuntimeEventBusTest {

    private RuntimeEventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new RuntimeEventBus("test-ling");
    }

    @Nested
    @DisplayName("订阅和发布")
    class SubscribeAndPublishTests {

        @Test
        @DisplayName("订阅后应能收到事件")
        void subscriberShouldReceiveEvent() {
            AtomicReference<RuntimeEvent.InstanceUpgrading> received = new AtomicReference<>();

            eventBus.subscribe(RuntimeEvent.InstanceUpgrading.class, received::set);
            eventBus.publish(new RuntimeEvent.InstanceUpgrading("test-ling", "1.0.0"));

            assertNotNull(received.get());
            assertEquals("1.0.0", received.get().newVersion());
        }

        @Test
        @DisplayName("不匹配的事件类型不应触发")
        void nonMatchingEventShouldNotTrigger() {
            AtomicInteger count = new AtomicInteger(0);

            eventBus.subscribe(RuntimeEvent.InstanceUpgrading.class, e -> count.incrementAndGet());
            eventBus.publish(new RuntimeEvent.RuntimeShuttingDown("test-ling"));

            assertEquals(0, count.get());
        }

        @Test
        @DisplayName("多个订阅者都应收到事件")
        void multipleSubscribersShouldAllReceive() {
            AtomicInteger count = new AtomicInteger(0);

            eventBus.subscribe(RuntimeEvent.InstanceUpgrading.class, e -> count.incrementAndGet());
            eventBus.subscribe(RuntimeEvent.InstanceUpgrading.class, e -> count.incrementAndGet());
            eventBus.subscribe(RuntimeEvent.InstanceUpgrading.class, e -> count.incrementAndGet());

            eventBus.publish(new RuntimeEvent.InstanceUpgrading("test-ling", "1.0.0"));

            assertEquals(3, count.get());
        }
    }

    @Nested
    @DisplayName("取消订阅")
    class UnsubscribeTests {

        @Test
        @DisplayName("取消订阅后不应收到事件")
        void unsubscribedShouldNotReceive() {
            AtomicInteger count = new AtomicInteger(0);

            RuntimeEventBus.Subscription subscription =
                    eventBus.subscribe(RuntimeEvent.InstanceUpgrading.class, e -> count.incrementAndGet());

            eventBus.publish(new RuntimeEvent.InstanceUpgrading("test-ling", "1.0.0"));
            assertEquals(1, count.get());

            subscription.unsubscribe();

            eventBus.publish(new RuntimeEvent.InstanceUpgrading("test-ling", "2.0.0"));
            assertEquals(1, count.get()); // 仍然是 1
        }
    }

    @Nested
    @DisplayName("异常处理")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("订阅者抛出异常不应影响其他订阅者")
        void exceptionShouldNotAffectOthers() {
            AtomicInteger count = new AtomicInteger(0);

            eventBus.subscribe(RuntimeEvent.InstanceUpgrading.class, e -> {
                throw new RuntimeException("Oops!");
            });
            eventBus.subscribe(RuntimeEvent.InstanceUpgrading.class, e -> count.incrementAndGet());

            assertDoesNotThrow(() ->
                    eventBus.publish(new RuntimeEvent.InstanceUpgrading("test-ling", "1.0.0")));

            assertEquals(1, count.get());
        }
    }

    @Nested
    @DisplayName("清理")
    class ClearTests {

        @Test
        @DisplayName("clear 后不应有订阅者")
        void clearShouldRemoveAllSubscriptions() {
            eventBus.subscribe(RuntimeEvent.InstanceUpgrading.class, e -> {
            });
            eventBus.subscribe(RuntimeEvent.RuntimeShuttingDown.class, e -> {
            });

            assertEquals(2, eventBus.getSubscriptionCount());

            eventBus.clear();

            assertEquals(0, eventBus.getSubscriptionCount());
        }
    }

    @Nested
    @DisplayName("事件类型")
    class EventTypeTests {

        @Test
        @DisplayName("InvocationCompleted 事件应包含正确信息")
        void invocationCompletedShouldHaveCorrectInfo() {
            AtomicReference<RuntimeEvent.InvocationCompleted> received = new AtomicReference<>();

            eventBus.subscribe(RuntimeEvent.InvocationCompleted.class, received::set);
            eventBus.publish(new RuntimeEvent.InvocationCompleted("test-ling", "test:hello", 100, true));

            assertNotNull(received.get());
            assertEquals("test:hello", received.get().fqsid());
            assertEquals(100, received.get().durationMs());
            assertTrue(received.get().success());
        }
    }
}