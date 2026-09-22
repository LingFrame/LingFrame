package com.lingframe.core.fsm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 状态机转换历史测试。
 * 覆盖：记录追加、快照查询、环形覆盖、并发安全、容量配置。
 */
@DisplayName("状态机转换历史测试")
class StateMachineHistoryTest {

    /**
     * 测试用简易状态枚举
     */
    private enum TestState {
        A, B, C, D
    }

    private static final Map<TestState, Set<TestState>> TRANSITIONS;

    static {
        Map<TestState, Set<TestState>> m = new EnumMap<>(TestState.class);
        m.put(TestState.A, EnumSet.of(TestState.B));
        m.put(TestState.B, EnumSet.of(TestState.C, TestState.A));
        m.put(TestState.C, EnumSet.of(TestState.D, TestState.A));
        m.put(TestState.D, EnumSet.of(TestState.A));
        TRANSITIONS = Collections.unmodifiableMap(m);
    }

    private StateMachine<TestState> newMachine(String id, int historyCapacity) {
        return new StateMachine<>(id, TestState.A, TRANSITIONS, historyCapacity);
    }

    private StateMachine<TestState> newMachine(String id) {
        return new StateMachine<>(id, TestState.A, TRANSITIONS);
    }

    @Nested
    @DisplayName("基础记录功能")
    class BasicRecording {

        @Test
        @DisplayName("初始状态无历史记录")
        void noHistoryInitially() {
            StateMachine<TestState> fsm = newMachine("test");
            assertTrue(fsm.history().isEmpty());
            assertNull(fsm.lastTransition());
        }

        @Test
        @DisplayName("单次转换产生一条记录")
        void singleTransition() {
            StateMachine<TestState> fsm = newMachine("test");
            fsm.transition(TestState.B);

            List<TransitionRecord<TestState>> h = fsm.history();
            assertEquals(1, h.size());
            assertEquals(TestState.A, h.get(0).from());
            assertEquals(TestState.B, h.get(0).to());
            assertEquals("test", h.get(0).contextId());
            assertTrue(h.get(0).timestamp() > 0);
        }

        @Test
        @DisplayName("多次转换按时间顺序记录")
        void multipleTransitions() {
            StateMachine<TestState> fsm = newMachine("test");
            fsm.transition(TestState.B);
            fsm.transition(TestState.C);
            fsm.transition(TestState.D);

            List<TransitionRecord<TestState>> h = fsm.history();
            assertEquals(3, h.size());
            assertEquals(TestState.A, h.get(0).from());
            assertEquals(TestState.B, h.get(1).from());
            assertEquals(TestState.C, h.get(2).from());
        }

        @Test
        @DisplayName("lastTransition 返回最近一条")
        void lastTransition() {
            StateMachine<TestState> fsm = newMachine("test");
            fsm.transition(TestState.B);
            fsm.transition(TestState.C);

            TransitionRecord<TestState> last = fsm.lastTransition();
            assertNotNull(last);
            assertEquals(TestState.B, last.from());
            assertEquals(TestState.C, last.to());
        }

        @Test
        @DisplayName("幂等转换不产生记录")
        void idempotentNoRecord() {
            StateMachine<TestState> fsm = newMachine("test");
            fsm.transition(TestState.B);
            fsm.transition(TestState.B); // 幂等

            assertEquals(1, fsm.history().size());
        }

        @Test
        @DisplayName("非法转换不产生记录")
        void illegalNoRecord() {
            StateMachine<TestState> fsm = newMachine("test");
            fsm.transition(TestState.C); // A→C 非法

            assertTrue(fsm.history().isEmpty());
        }
    }

    @Nested
    @DisplayName("环形缓冲区覆盖")
    class RingBufferOverflow {

        @Test
        @DisplayName("容量满时覆盖最旧记录")
        void overwriteOldest() {
            // 容量 3 会被向上取整到 4（2 的幂）
            StateMachine<TestState> fsm = newMachine("test", 3);
            // A→B→C→D→A→B 共 5 次转换，实际容量 4
            fsm.transition(TestState.B);
            fsm.transition(TestState.C);
            fsm.transition(TestState.D);
            fsm.transition(TestState.A);
            fsm.transition(TestState.B);

            List<TransitionRecord<TestState>> h = fsm.history();
            assertEquals(4, h.size());
            // 最旧的 A→B 被覆盖，保留最后 4 条：B→C, C→D, D→A, A→B
            assertEquals(TestState.B, h.get(0).from());
            assertEquals(TestState.C, h.get(1).from());
            assertEquals(TestState.D, h.get(2).from());
            assertEquals(TestState.A, h.get(3).from());
        }

        @Test
        @DisplayName("容量为 1 时只保留最近一条")
        void capacityOne() {
            StateMachine<TestState> fsm = newMachine("test", 1);
            fsm.transition(TestState.B);
            fsm.transition(TestState.C);

            List<TransitionRecord<TestState>> h = fsm.history();
            assertEquals(1, h.size());
            assertEquals(TestState.B, h.get(0).from());
            assertEquals(TestState.C, h.get(0).to());
        }
    }

    @Nested
    @DisplayName("快照不可变性")
    class SnapshotImmutability {

        @Test
        @DisplayName("返回的列表不可修改")
        void unmodifiableList() {
            StateMachine<TestState> fsm = newMachine("test");
            fsm.transition(TestState.B);

            List<TransitionRecord<TestState>> h = fsm.history();
            assertThrows(UnsupportedOperationException.class, () -> h.add(null));
        }

        @Test
        @DisplayName("快照后新转换不影响已返回的列表")
        void snapshotIsolation() {
            StateMachine<TestState> fsm = newMachine("test");
            fsm.transition(TestState.B);
            List<TransitionRecord<TestState>> snap1 = fsm.history();

            fsm.transition(TestState.C);
            List<TransitionRecord<TestState>> snap2 = fsm.history();

            assertEquals(1, snap1.size());
            assertEquals(2, snap2.size());
        }
    }

    @Nested
    @DisplayName("并发安全")
    class Concurrency {

        @Test
        @DisplayName("多线程并发转换，历史无丢失无损坏")
        void concurrentTransitions() throws Exception {
            int threadCount = 8;
            int transitionsPerThread = 100;
            StateMachine<TestState> fsm = newMachine("concurrent-test");
            AtomicInteger successCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < transitionsPerThread; i++) {
                            TestState current = fsm.current();
                            // 尝试合法转换
                            Set<TestState> allowed = TRANSITIONS.getOrDefault(current, Collections.emptySet());
                            for (TestState target : allowed) {
                                TransitionResult<TestState> result = fsm.transition(target);
                                if (result.isSuccess() && result.from() != result.target()) {
                                    successCount.incrementAndGet();
                                }
                                break;
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            // 历史记录数应等于成功转换数（不超过默认容量 64）
            int expectedSize = Math.min(successCount.get(), 64);
            assertEquals(expectedSize, fsm.history().size());
        }
    }

    @Nested
    @DisplayName("显式期望值转换")
    class ExplicitExpectedTransition {

        @Test
        @DisplayName("显式期望值转换也记录历史")
        void explicitTransitionRecords() {
            StateMachine<TestState> fsm = newMachine("test");
            TransitionResult<TestState> result = fsm.transition(TestState.A, TestState.B);

            assertTrue(result.isSuccess());
            assertEquals(1, fsm.history().size());
            assertEquals(TestState.A, fsm.history().get(0).from());
            assertEquals(TestState.B, fsm.history().get(0).to());
        }
    }
}
