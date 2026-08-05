package com.lingframe.core.fsm;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.InstanceDestroyedEvent;
import com.lingframe.core.event.InstanceStateChangedEvent;
import com.lingframe.core.event.RuntimeStateChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RuntimeCoordinator 测试。
 * 覆盖：注册/注销、事件联动聚合、STOPPING意图态压制、purge、并发安全。
 */
@DisplayName("RuntimeCoordinator 测试")
class RuntimeCoordinatorTest {

    private EventBus eventBus;
    private RuntimeCoordinator coordinator;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        coordinator = new RuntimeCoordinator(eventBus);
        coordinator.start();
    }

    // ==================== 注册与查询 ====================

    @Nested
    @DisplayName("注册与查询")
    class RegisterAndQuery {

        @Test
        @DisplayName("注册灵元后状态机初始为 INACTIVE")
        void registerInitialInactive() {
            StateMachine<RuntimeStatus> fsm = coordinator.register("ling-1");
            assertNotNull(fsm);
            assertEquals(RuntimeStatus.INACTIVE, fsm.current());
            assertEquals(RuntimeStatus.INACTIVE, coordinator.getStatus("ling-1"));
        }

        @Test
        @DisplayName("重复注册返回同一状态机（幂等）")
        void registerIdempotent() {
            StateMachine<RuntimeStatus> first = coordinator.register("ling-1");
            StateMachine<RuntimeStatus> second = coordinator.register("ling-1");
            assertSame(first, second);
        }

        @Test
        @DisplayName("未注册灵元 getStatus 返回 null")
        void getStatusUnknown() {
            assertNull(coordinator.getStatus("unknown"));
        }

        }

    // ==================== 事件联动聚合 ====================

    @Nested
    @DisplayName("实例事件联动聚合")
    class EventAggregation {

        @Test
        @DisplayName("实例 READY 事件驱动运行时 INACTIVE → ACTIVE")
        void instanceReadyDrivesActive() {
            coordinator.register("ling-1");
            assertEquals(RuntimeStatus.INACTIVE, coordinator.getStatus("ling-1"));

            // 发布实例 READY 事件
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.STARTING, InstanceStatus.READY));

            assertEquals(RuntimeStatus.ACTIVE, coordinator.getStatus("ling-1"));
        }

        @Test
        @DisplayName("多实例全部 READY 仍为 ACTIVE")
        void multipleReadyStillActive() {
            coordinator.register("ling-1");

            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.STARTING, InstanceStatus.READY));
            assertEquals(RuntimeStatus.ACTIVE, coordinator.getStatus("ling-1"));

            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v2", "v2",
                    InstanceStatus.STARTING, InstanceStatus.READY));
            assertEquals(RuntimeStatus.ACTIVE, coordinator.getStatus("ling-1"));
        }

        @Test
        @DisplayName("实例 ERROR 事件驱动运行时 DEGRADED")
        void instanceErrorDrivesDegraded() {
            coordinator.register("ling-1");

            // 先 READY → ACTIVE
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.STARTING, InstanceStatus.READY));
            assertEquals(RuntimeStatus.ACTIVE, coordinator.getStatus("ling-1"));

            // 然后 ERROR → DEGRADED
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.READY, InstanceStatus.ERROR));
            assertEquals(RuntimeStatus.DEGRADED, coordinator.getStatus("ling-1"));
        }

        @Test
        @DisplayName("实例 DEAD 事件移出快照")
        void instanceDeadRemovesFromSnapshot() {
            coordinator.register("ling-1");

            // READY → ACTIVE
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.STARTING, InstanceStatus.READY));
            assertEquals(RuntimeStatus.ACTIVE, coordinator.getStatus("ling-1"));

            // DEAD → INACTIVE（无实例了）
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.STOPPING, InstanceStatus.DEAD));
            assertEquals(RuntimeStatus.INACTIVE, coordinator.getStatus("ling-1"));
        }

        @Test
        @DisplayName("InstanceDestroyedEvent 兜底清理快照")
        void instanceDestroyedCleansSnapshot() {
            coordinator.register("ling-1");

            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.STARTING, InstanceStatus.READY));
            assertEquals(RuntimeStatus.ACTIVE, coordinator.getStatus("ling-1"));

            // 销毁事件移出版本快照
            eventBus.publish(new InstanceDestroyedEvent("ling-1", "v1", "v1"));
            assertEquals(RuntimeStatus.INACTIVE, coordinator.getStatus("ling-1"));
        }

        @Test
        @DisplayName("未注册灵元收到事件时防御性注册")
        void unregisteredEventDoesNotCreateGhostMachine() {
            // 不显式 register，直接发事件：不得防御性创建 FSM（避免 unregister 后迟到事件复活）
            eventBus.publish(new InstanceStateChangedEvent("ling-auto", "v1", "v1",
                    InstanceStatus.STARTING, InstanceStatus.READY));

            assertNull(coordinator.getStatus("ling-auto"));
        }

        @Test
        @DisplayName("灵核 lingcore-app 事件不得注册进 RuntimeCoordinator")
        void lingCoreEventsNeverRegister() {
            eventBus.publish(new InstanceStateChangedEvent("lingcore-app", "id-1", "permanent",
                    InstanceStatus.STARTING, InstanceStatus.READY));
            assertNull(coordinator.getStatus("lingcore-app"));
            assertThrows(IllegalArgumentException.class, () -> coordinator.register("lingcore-app"));
        }

        @Test
        @DisplayName("同 version 不同 instanceId 的双实例互不覆盖快照")
        void sameVersionDifferentInstanceIdsDoNotCollide() {
            coordinator.register("ling-1");
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "id-a", "1.0.0",
                    InstanceStatus.STARTING, InstanceStatus.READY));
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "id-b", "1.0.0",
                    InstanceStatus.STARTING, InstanceStatus.READY));
            assertEquals(RuntimeStatus.ACTIVE, coordinator.getStatus("ling-1"));

            // 旧实例 DEAD 不得抹掉新实例
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "id-a", "1.0.0",
                    InstanceStatus.STOPPING, InstanceStatus.DEAD));
            assertEquals(RuntimeStatus.ACTIVE, coordinator.getStatus("ling-1"));

            eventBus.publish(new InstanceStateChangedEvent("ling-1", "id-b", "1.0.0",
                    InstanceStatus.STOPPING, InstanceStatus.DEAD));
            assertEquals(RuntimeStatus.INACTIVE, coordinator.getStatus("ling-1"));
        }
    }

    @Nested
    @DisplayName("unregister 确定性收口")
    class UnregisterTests {

        @Test
        @DisplayName("unregister 从 INACTIVE 确定性清除 registration")
        void unregisterFromInactiveClearsRegistration() {
            coordinator.register("ling-x");
            assertEquals(RuntimeStatus.INACTIVE, coordinator.getStatus("ling-x"));
            assertTrue(coordinator.unregister("ling-x"));
            assertNull(coordinator.getStatus("ling-x"));
            assertFalse(coordinator.unregister("ling-x"));
        }

        @Test
        @DisplayName("unregister 从 ACTIVE 经 STOPPING/REMOVED 后清除")
        void unregisterFromActiveClearsRegistration() {
            coordinator.register("ling-y");
            eventBus.publish(new InstanceStateChangedEvent("ling-y", "id-1", "v1",
                    InstanceStatus.STARTING, InstanceStatus.READY));
            assertEquals(RuntimeStatus.ACTIVE, coordinator.getStatus("ling-y"));
            assertTrue(coordinator.unregister("ling-y"));
            assertNull(coordinator.getStatus("ling-y"));
        }
    }

    // ==================== STOPPING 意图态 ====================

    @Nested
    @DisplayName("STOPPING 意图态")
    class StoppingState {

        @Test
        @DisplayName("shutdown 驱动运行时进入 STOPPING")
        void shutdownDrivesStopping() {
            coordinator.register("ling-1");
            // 先进入 ACTIVE
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.STARTING, InstanceStatus.READY));
            assertEquals(RuntimeStatus.ACTIVE, coordinator.getStatus("ling-1"));

            coordinator.shutdown("ling-1");
            assertEquals(RuntimeStatus.STOPPING, coordinator.getStatus("ling-1"));
        }

        @Test
        @DisplayName("STOPPING 下实例变好不会拉回 ACTIVE")
        void stoppingBlocksRecovery() {
            coordinator.register("ling-1");

            // READY → ACTIVE
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.STARTING, InstanceStatus.READY));
            // shutdown → STOPPING
            coordinator.shutdown("ling-1");
            assertEquals(RuntimeStatus.STOPPING, coordinator.getStatus("ling-1"));

            // 实例恢复 READY，但 STOPPING 压制，不应回到 ACTIVE
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.ERROR, InstanceStatus.READY));
            assertEquals(RuntimeStatus.STOPPING, coordinator.getStatus("ling-1"));
        }

        @Test
        @DisplayName("STOPPING 下强制 transition ACTIVE/DEGRADED 被拒绝")
        void stoppingTransitionRejected() {
            coordinator.register("ling-1");
            coordinator.transition("ling-1", RuntimeStatus.ACTIVE);
            coordinator.transition("ling-1", RuntimeStatus.STOPPING);
            assertEquals(RuntimeStatus.STOPPING, coordinator.getStatus("ling-1"));

            TransitionResult<RuntimeStatus> resActive = coordinator.transition("ling-1", RuntimeStatus.ACTIVE);
            assertTrue(resActive.isIllegal());
            
            TransitionResult<RuntimeStatus> resDegraded = coordinator.transition("ling-1", RuntimeStatus.DEGRADED);
            assertTrue(resDegraded.isIllegal());

            assertEquals(RuntimeStatus.STOPPING, coordinator.getStatus("ling-1"));
        }

        @Test
        @DisplayName("STOPPING 下所有实例 DEAD 后自动进入 REMOVED")
        void stoppingAllDeadToRemoved() {
            coordinator.register("ling-1");

            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.STARTING, InstanceStatus.READY));
            coordinator.shutdown("ling-1");

            // 所有实例 DEAD
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.STOPPING, InstanceStatus.DEAD));
            assertEquals(RuntimeStatus.REMOVED, coordinator.getStatus("ling-1"));
        }

        @Test
        @DisplayName("shutdown 未知灵元不抛异常")
        void shutdownUnknownNoException() {
            assertDoesNotThrow(() -> coordinator.shutdown("unknown"));
        }
    }

    // ==================== 主动转换 ====================

    @Nested
    @DisplayName("主动状态转换")
    class ManualTransition {

        @Test
        @DisplayName("transition 驱动合法转换并返回成功")
        void transitionSuccess() {
            coordinator.register("ling-1");
            TransitionResult<RuntimeStatus> result = coordinator.transition("ling-1", RuntimeStatus.ACTIVE);
            assertTrue(result.isSuccess());
            assertEquals(RuntimeStatus.INACTIVE, result.from());
            assertEquals(RuntimeStatus.ACTIVE, result.target());
        }

        @Test
        @DisplayName("transition 非法转换返回 ILLEGAL")
        void transitionIllegal() {
            coordinator.register("ling-1");
            // INACTIVE → STOPPING 不在转换表中
            TransitionResult<RuntimeStatus> result = coordinator.transition("ling-1", RuntimeStatus.STOPPING);
            assertTrue(result.isIllegal());
        }

        @Test
        @DisplayName("transition 未知灵元返回 ILLEGAL")
        void transitionUnknown() {
            TransitionResult<RuntimeStatus> result = coordinator.transition("unknown", RuntimeStatus.ACTIVE);
            assertTrue(result.isIllegal());
        }
    }

    // ==================== purge ====================

    @Nested
    @DisplayName("purge 清理")
    class Purge {

        @Test
        @DisplayName("REMOVED 状态的灵元可被 purge")
        void purgeRemoved() {
            coordinator.register("ling-1");
            coordinator.transition("ling-1", RuntimeStatus.ACTIVE);
            coordinator.transition("ling-1", RuntimeStatus.STOPPING);
            coordinator.transition("ling-1", RuntimeStatus.REMOVED);

            coordinator.purge("ling-1");
            assertNull(coordinator.getStatus("ling-1"));
        }

        @Test
        @DisplayName("非 REMOVED 状态的灵元不能被 purge")
        void purgeNonRemovedIgnored() {
            coordinator.register("ling-1");
            coordinator.purge("ling-1");
            // 仍然存在
            assertNotNull(coordinator.getStatus("ling-1"));
        }
    }

    // ==================== 事件发布 ====================

    @Nested
    @DisplayName("运行时状态变更事件发布")
    class EventPublishing {

        @Test
        @DisplayName("运行时状态变更发布 RuntimeStateChangedEvent")
        void publishesRuntimeStateChanged() {
            AtomicReference<RuntimeStateChangedEvent> captured = new AtomicReference<>();
            eventBus.subscribeGlobal(RuntimeStateChangedEvent.class, captured::set);

            coordinator.register("ling-1");
            coordinator.transition("ling-1", RuntimeStatus.ACTIVE);

            // 事件是异步发布的，等待一下
            awaitOrFail(captured);

            RuntimeStateChangedEvent event = captured.get();
            assertNotNull(event);
            assertEquals("ling-1", event.getLingId());
            assertEquals(RuntimeStatus.INACTIVE, event.getFrom());
            assertEquals(RuntimeStatus.ACTIVE, event.getTo());
        }
    }

    // ==================== 生命周期 ====================

    @Nested
    @DisplayName("协调器生命周期")
    class Lifecycle {

        @Test
        @DisplayName("stop 后不再响应实例事件")
        void stopDisablesEventListening() {
            coordinator.register("ling-1");
            coordinator.stop();

            // 发布事件，不应触发状态变更
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.STARTING, InstanceStatus.READY));

            // 仍为 INACTIVE（stop 后事件不再被处理）
            assertEquals(RuntimeStatus.INACTIVE, coordinator.getStatus("ling-1"));
        }
    }

    // ==================== NPE 防御（P2-1/P2-2）====================

    @Nested
    @DisplayName("NPE 防御")
    class NullSafetyDefense {

        @Test
        @DisplayName("eventBus 为 null 时构造 fail-fast 抛 NullPointerException")
        void constructWithNullEventBusThrowsNpe() {
            // 契约：eventBus 不允许为 null，构造时直接拒绝（fail-fast）
            assertThrows(NullPointerException.class, () -> new RuntimeCoordinator((EventBus) null));
        }

        @Test
        @DisplayName("purge 后收到迟到的实例事件不抛异常且不复活 FSM")
        void lateEventAfterPurgeNoException() {
            coordinator.register("ling-late");
            coordinator.transition("ling-late", RuntimeStatus.ACTIVE);
            coordinator.transition("ling-late", RuntimeStatus.STOPPING);
            coordinator.transition("ling-late", RuntimeStatus.REMOVED);
            coordinator.purge("ling-late");

            assertDoesNotThrow(() -> eventBus.publish(new InstanceStateChangedEvent("ling-late", "v1", "v1",
                    InstanceStatus.STARTING, InstanceStatus.READY)));
            assertNull(coordinator.getStatus("ling-late"));
        }

        @Test
        @DisplayName("unregister 后迟到事件不得复活 ghost FSM")
        void lateEventAfterUnregisterDoesNotResurrect() {
            coordinator.register("ling-gone");
            eventBus.publish(new InstanceStateChangedEvent("ling-gone", "id-1", "v1",
                    InstanceStatus.STARTING, InstanceStatus.READY));
            assertEquals(RuntimeStatus.ACTIVE, coordinator.getStatus("ling-gone"));
            assertTrue(coordinator.unregister("ling-gone"));
            assertNull(coordinator.getStatus("ling-gone"));

            eventBus.publish(new InstanceStateChangedEvent("ling-gone", "id-1", "v1",
                    InstanceStatus.READY, InstanceStatus.DEAD));
            assertNull(coordinator.getStatus("ling-gone"));
        }

        @Test
        @DisplayName("并发 purge 与事件处理不抛 NPE")
        void concurrentPurgeAndEventNoNpe() throws Exception {
            // 验证：purge 与事件处理的竞态下不会 NPE
            coordinator.register("ling-race");
            coordinator.transition("ling-race", RuntimeStatus.ACTIVE);
            coordinator.transition("ling-race", RuntimeStatus.STOPPING);
            coordinator.transition("ling-race", RuntimeStatus.REMOVED);

            int iterations = 200;
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger npeCount = new AtomicInteger(0);

            // 线程1：反复发布事件
            executor.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        try {
                            eventBus.publish(new InstanceStateChangedEvent("ling-race", "v1", "v1",
                                    InstanceStatus.STARTING, InstanceStatus.READY));
                        } catch (NullPointerException e) {
                            npeCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // 线程2：反复 purge（状态为 REMOVED 时才真正移除）
            executor.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        coordinator.purge("ling-race");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

            assertEquals(0, npeCount.get(), "并发 purge 与事件处理不应抛 NPE");
        }
    }

    // ==================== 并发安全 ====================

    @Nested
    @DisplayName("并发安全")
    class Concurrency {

        @Test
        @DisplayName("并发注册同一灵元幂等")
        void concurrentRegisterIdempotent() throws Exception {
            int threadCount = 8;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            StateMachine<RuntimeStatus>[] results = new StateMachine[threadCount];

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        results[idx] = coordinator.register("ling-concurrent");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
            executor.shutdown();

            // 所有线程拿到的应该是同一个状态机实例
            StateMachine<RuntimeStatus> expected = results[0];
            assertNotNull(expected);
            for (StateMachine<RuntimeStatus> r : results) {
                assertSame(expected, r);
            }
        }

        @Test
        @DisplayName("并发事件驱动状态收敛一致")
        void concurrentEventConvergence() throws Exception {
            coordinator.register("ling-conv");

            int threadCount = 4;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        // 所有线程都发 READY 事件
                        eventBus.publish(new InstanceStateChangedEvent("ling-conv", "v1", "v1",
                                InstanceStatus.STARTING, InstanceStatus.READY));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
            executor.shutdown();

            // 所有事件都是 READY，最终状态应收敛为 ACTIVE
            RuntimeStatus status = coordinator.getStatus("ling-conv");
            assertEquals(RuntimeStatus.ACTIVE, status,
                    "所有 READY 事件后状态应收敛为 ACTIVE");
        }
    }

    // ==================== 自定义策略 ====================

    @Nested
    @DisplayName("自定义聚合策略")
    class CustomPolicy {

        @Test
        @DisplayName("自定义策略可覆盖评估逻辑")
        void customPolicyOverridesEvaluation() {
            // 策略：只要有任何实例就返回 ACTIVE（不管实例状态）
            RuntimeEvaluationPolicy alwaysActive = (current, instances) -> RuntimeStatus.ACTIVE;

            RuntimeCoordinator custom = new RuntimeCoordinator(eventBus, alwaysActive);
            custom.start();
            custom.register("ling-custom");

            // 即使实例是 ERROR，策略也返回 ACTIVE
            eventBus.publish(new InstanceStateChangedEvent("ling-custom", "v1", "v1",
                    InstanceStatus.STARTING, InstanceStatus.ERROR));

            assertEquals(RuntimeStatus.ACTIVE, custom.getStatus("ling-custom"));
            custom.stop();
        }
    }

    // ==================== transition vs reevaluate 冲突契约 ====================

    @Nested
    @DisplayName("transition 与 reevaluate 冲突契约")
    class TransitionVsReevaluateConflict {

        @Test
        @DisplayName("STOPPING 下 transition 不会被 reevaluate 拉回 ACTIVE")
        void stoppingTransitionNotOverriddenByReevaluate() {
            coordinator.register("ling-1");

            // 先进入 ACTIVE
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.STARTING, InstanceStatus.READY));
            assertEquals(RuntimeStatus.ACTIVE, coordinator.getStatus("ling-1"));

            // 运维触发 shutdown → STOPPING
            coordinator.shutdown("ling-1");
            assertEquals(RuntimeStatus.STOPPING, coordinator.getStatus("ling-1"));

            // 实例 READY 事件触发 reevaluate，但 STOPPING 压制不应回到 ACTIVE
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.ERROR, InstanceStatus.READY));
            assertEquals(RuntimeStatus.STOPPING, coordinator.getStatus("ling-1"));
        }

        @Test
        @DisplayName("DEGRADED 下 transition(ACTIVE) 不会被后续 ERROR 事件覆盖")
        void degradedTransitionActiveNotOverriddenByErrorEvent() {
            coordinator.register("ling-1");

            // READY → ACTIVE
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.STARTING, InstanceStatus.READY));
            assertEquals(RuntimeStatus.ACTIVE, coordinator.getStatus("ling-1"));

            // ERROR → DEGRADED
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.READY, InstanceStatus.ERROR));
            assertEquals(RuntimeStatus.DEGRADED, coordinator.getStatus("ling-1"));

            // 运维主动 transition(ACTIVE)
            TransitionResult<RuntimeStatus> result = coordinator.transition("ling-1", RuntimeStatus.ACTIVE);
            assertTrue(result.isSuccess());
            assertEquals(RuntimeStatus.ACTIVE, coordinator.getStatus("ling-1"));

            // 后续 ERROR 事件触发 reevaluate 可能再次降级——这是预期行为
            // 但关键点是：运维的 transition 在那一刻确实生效了
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.READY, InstanceStatus.ERROR));
            assertEquals(RuntimeStatus.DEGRADED, coordinator.getStatus("ling-1"));
        }

        @Test
        @DisplayName("有 READY 实例时 transition(INACTIVE) 可被 reevaluate 拉回 ACTIVE（事实优先，不发明停流状态）")
        void inactiveWithReadyInstancesCanReevaluateToActive() {
            coordinator.register("ling-1");
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.STARTING, InstanceStatus.READY));
            assertEquals(RuntimeStatus.ACTIVE, coordinator.getStatus("ling-1"));

            // 控制面 transition(INACTIVE) 成功；实例仍 READY 时后续事件可再聚合为 ACTIVE
            TransitionResult<RuntimeStatus> toInactive = coordinator.transition("ling-1", RuntimeStatus.INACTIVE);
            assertTrue(toInactive.isSuccess());
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.READY, InstanceStatus.READY));
            assertEquals(RuntimeStatus.ACTIVE, coordinator.getStatus("ling-1"));
        }

        @Test
        @DisplayName("INACTIVE 下 transition(ACTIVE) 后实例 READY 事件保持 ACTIVE")
        void inactiveTransitionActiveThenInstanceReadyStaysActive() {
            coordinator.register("ling-1");
            assertEquals(RuntimeStatus.INACTIVE, coordinator.getStatus("ling-1"));

            coordinator.transition("ling-1", RuntimeStatus.ACTIVE);
            assertEquals(RuntimeStatus.ACTIVE, coordinator.getStatus("ling-1"));

            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.STARTING, InstanceStatus.READY));
            assertEquals(RuntimeStatus.ACTIVE, coordinator.getStatus("ling-1"));
        }

        @Test
        @DisplayName("transition(ACTIVE) 后所有实例 DEAD 触发 reevaluate 回 INACTIVE")
        void transitionActiveThenAllDeadReevaluateInactive() {
            coordinator.register("ling-1");

            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.STARTING, InstanceStatus.READY));
            assertEquals(RuntimeStatus.ACTIVE, coordinator.getStatus("ling-1"));

            // 所有实例 DEAD → reevaluate 推回 INACTIVE
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.STOPPING, InstanceStatus.DEAD));
            assertEquals(RuntimeStatus.INACTIVE, coordinator.getStatus("ling-1"));
        }

        @Test
        @DisplayName("RECOVERING 下 transition(ACTIVE) 优先于 reevaluate")
        void recoveringTransitionActiveOverridesReevaluate() {
            coordinator.register("ling-1");

            // READY → ACTIVE
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.STARTING, InstanceStatus.READY));
            // ERROR → DEGRADED
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.READY, InstanceStatus.ERROR));
            assertEquals(RuntimeStatus.DEGRADED, coordinator.getStatus("ling-1"));

            // 运维触发 RECOVERING
            coordinator.transition("ling-1", RuntimeStatus.RECOVERING);
            assertEquals(RuntimeStatus.RECOVERING, coordinator.getStatus("ling-1"));

            // 实例恢复 READY → reevaluate 推到 ACTIVE
            eventBus.publish(new InstanceStateChangedEvent("ling-1", "v1", "v1",
                    InstanceStatus.ERROR, InstanceStatus.READY));
            assertEquals(RuntimeStatus.ACTIVE, coordinator.getStatus("ling-1"));
        }
    }

    // ==================== 辅助方法 ====================

    private void awaitOrFail(AtomicReference<?> ref) {
        long deadline = System.currentTimeMillis() + 2000;
        while (ref.get() == null && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
        assertNotNull(ref.get(), "Timeout waiting for async event");
    }
}
