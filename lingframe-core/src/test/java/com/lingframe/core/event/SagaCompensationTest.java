package com.lingframe.core.event;

import com.lingframe.api.event.LingEvent;
import com.lingframe.api.event.LingEventListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saga 补偿编排实证：模式 2 多私有库跨灵元的补偿链与 traceId 幂等去重。
 * <p>
 * 场景：下单灵元（order-ling）编排跨灵元 Saga——扣库存（stock-ling）+ 扣余额
 * （wallet-ling）双私有库独立提交；余额扣减失败时按 traceId 发布补偿事件回滚库存，
 * 补偿事件重复投递（重放）时按 traceId 幂等去重，补偿动作只执行一次。
 */
@DisplayName("Saga：跨灵元补偿链与 traceId 幂等去重")
class SagaCompensationTest {

    /** 库存扣减成功事件 */
    private static final class InventoryDeductedEvent implements LingEvent {
        private final String orderId;

        InventoryDeductedEvent(String orderId) {
            this.orderId = orderId;
        }

        String getOrderId() {
            return orderId;
        }
    }

    /** 余额扣减成功事件 */
    private static final class WalletDeductedEvent implements LingEvent {
        private final String orderId;

        WalletDeductedEvent(String orderId) {
            this.orderId = orderId;
        }

        String getOrderId() {
            return orderId;
        }
    }

    /** 库存补偿事件：携带 traceId（幂等键）与订单号 */
    private static final class InventoryCompensationEvent implements LingEvent {
        private final String traceId;
        private final String orderId;

        InventoryCompensationEvent(String traceId, String orderId) {
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

    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
    }

    @AfterEach
    void tearDown() {
        eventBus.shutdown();
    }

    @Nested
    @DisplayName("正常回滚：后置灵元失败触发前置补偿")
    class NormalRollback {

        @Test
        @DisplayName("余额扣减失败 → 库存补偿事件发出并执行，补偿计数正确")
        void failedWalletTriggersInventoryCompensation() {
            // 库存侧补偿处理器：记录补偿执行次数
            AtomicInteger inventoryCompensations = new AtomicInteger();
            eventBus.subscribe("stock-ling", InventoryCompensationEvent.class,
                    e -> inventoryCompensations.incrementAndGet());

            // 余额灵元：扣减成功但后续提交失败 → 触发库存补偿
            eventBus.subscribe("wallet-ling", WalletDeductedEvent.class, new LingEventListener<WalletDeductedEvent>() {
                @Override
                public void onEvent(WalletDeductedEvent event) {
                    // 模拟余额扣减后提交失败（补偿编排入口）
                    String traceId = "saga-001";
                    eventBus.publish(new InventoryCompensationEvent(traceId, event.getOrderId()));
                }
            });

            // Saga 编排：先扣库存（成功）→ 再扣余额（触发失败路径）
            String orderId = "order-saga-001";
            eventBus.subscribe("stock-ling", InventoryDeductedEvent.class,
                    e -> eventBus.publish(new WalletDeductedEvent(e.getOrderId())));
            eventBus.publish(new InventoryDeductedEvent(orderId));

            // 补偿链收敛：库存补偿执行 1 次
            assertEquals(1, inventoryCompensations.get(),
                    "wallet failure should trigger exactly one inventory compensation");
        }
    }

    @Nested
    @DisplayName("幂等重放：同 traceId 去重")
    class IdempotentReplay {

        @Test
        @DisplayName("同 traceId 补偿事件重复投递（重放）→ 补偿只执行一次（幂等去重）")
        void sameTraceIdReplayExecutesOnce() {
            Set<String> processedTraces = ConcurrentHashMap.newKeySet();
            AtomicInteger compensations = new AtomicInteger();
            eventBus.subscribe("stock-ling", InventoryCompensationEvent.class,
                    new LingEventListener<InventoryCompensationEvent>() {
                        @Override
                        public void onEvent(InventoryCompensationEvent event) {
                            // traceId 幂等去重：已处理的 traceId 不再重复补偿
                            if (processedTraces.add(event.getTraceId())) {
                                compensations.incrementAndGet();
                            }
                        }
                    });

            String traceId = "saga-002";
            // 同 traceId 事件重复投递 3 次（模拟补偿重放 / 重复确认）
            eventBus.publish(new InventoryCompensationEvent(traceId, "order-saga-002"));
            eventBus.publish(new InventoryCompensationEvent(traceId, "order-saga-002"));
            eventBus.publish(new InventoryCompensationEvent(traceId, "order-saga-002"));

            assertEquals(1, compensations.get(),
                    "same traceId replay should be deduplicated to exactly one compensation");
        }

        @Test
        @DisplayName("不同 traceId 各自执行补偿（互不干扰）")
        void differentTraceIdsEachExecuteOnce() {
            Set<String> processedTraces = ConcurrentHashMap.newKeySet();
            AtomicInteger compensations = new AtomicInteger();
            eventBus.subscribe("stock-ling", InventoryCompensationEvent.class,
                    new LingEventListener<InventoryCompensationEvent>() {
                        @Override
                        public void onEvent(InventoryCompensationEvent event) {
                            if (processedTraces.add(event.getTraceId())) {
                                compensations.incrementAndGet();
                            }
                        }
                    });

            eventBus.publish(new InventoryCompensationEvent("saga-003", "order-saga-003"));
            eventBus.publish(new InventoryCompensationEvent("saga-004", "order-saga-004"));
            eventBus.publish(new InventoryCompensationEvent("saga-005", "order-saga-005"));

            assertEquals(3, compensations.get(),
                    "distinct traceIds should each trigger exactly one compensation");
        }

        @Test
        @DisplayName("补偿事件携带 traceId：订阅方可循 traceId 判定幂等键")
        void compensationEventCarriesTraceId() {
            AtomicInteger seen = new AtomicInteger();
            eventBus.subscribe("stock-ling", InventoryCompensationEvent.class, new LingEventListener<InventoryCompensationEvent>() {
                @Override
                public void onEvent(InventoryCompensationEvent event) {
                    assertTrue(event.getTraceId() != null && !event.getTraceId().isEmpty(),
                            "compensation event must carry traceId for idempotency key");
                    seen.incrementAndGet();
                }
            });

            eventBus.publish(new InventoryCompensationEvent("saga-006", "order-saga-006"));

            assertEquals(1, seen.get());
        }
    }
}
