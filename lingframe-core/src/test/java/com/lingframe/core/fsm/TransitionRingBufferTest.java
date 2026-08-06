package com.lingframe.core.fsm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TransitionRingBuffer 测试。
 * 覆盖：追加/读取、环形覆盖、快照、容量2的幂对齐、边界条件。
 */
@DisplayName("TransitionRingBuffer 测试")
class TransitionRingBufferTest {

    private enum TestState { A, B, C }

    private TransitionRecord<TestState> record(TestState from, TestState to) {
        return new TransitionRecord<>("test-ctx", from, to, System.currentTimeMillis());
    }

    // ==================== 基本追加与读取 ====================

    @Nested
    @DisplayName("基本追加与读取")
    class BasicAppendAndRead {

        @Test
        @DisplayName("空缓冲区 latest 返回 null")
        void emptyLatestReturnsNull() {
            TransitionRingBuffer<TestState> buf = new TransitionRingBuffer<>(4);
            assertNull(buf.latest());
        }

        @Test
        @DisplayName("空缓冲区 snapshot 返回空列表")
        void emptySnapshotReturnsEmpty() {
            TransitionRingBuffer<TestState> buf = new TransitionRingBuffer<>(4);
            assertTrue(buf.snapshot().isEmpty());
        }

        @Test
        @DisplayName("追加一条后 latest 返回该记录")
        void appendOneLatest() {
            TransitionRingBuffer<TestState> buf = new TransitionRingBuffer<>(4);
            TransitionRecord<TestState> r = record(TestState.A, TestState.B);
            buf.append(r);

            assertEquals(r, buf.latest());
        }

        @Test
        @DisplayName("追加多条后 latest 返回最后一条")
        void appendMultipleLatest() {
            TransitionRingBuffer<TestState> buf = new TransitionRingBuffer<>(4);
            TransitionRecord<TestState> r1 = record(TestState.A, TestState.B);
            TransitionRecord<TestState> r2 = record(TestState.B, TestState.C);
            buf.append(r1);
            buf.append(r2);

            assertEquals(r2, buf.latest());
        }

        @Test
        @DisplayName("totalTransitions 计数正确")
        void totalTransitionsCount() {
            TransitionRingBuffer<TestState> buf = new TransitionRingBuffer<>(4);
            assertEquals(0, buf.totalTransitions());

            buf.append(record(TestState.A, TestState.B));
            assertEquals(1, buf.totalTransitions());

            buf.append(record(TestState.B, TestState.C));
            assertEquals(2, buf.totalTransitions());
        }
    }

    // ==================== 快照 ====================

    @Nested
    @DisplayName("快照读取")
    class Snapshot {

        @Test
        @DisplayName("快照按时间从旧到新排列")
        void snapshotOrdered() {
            TransitionRingBuffer<TestState> buf = new TransitionRingBuffer<>(4);
            TransitionRecord<TestState> r1 = record(TestState.A, TestState.B);
            TransitionRecord<TestState> r2 = record(TestState.B, TestState.C);
            TransitionRecord<TestState> r3 = record(TestState.C, TestState.A);
            buf.append(r1);
            buf.append(r2);
            buf.append(r3);

            List<TransitionRecord<TestState>> snap = buf.snapshot();
            assertEquals(3, snap.size());
            assertEquals(r1, snap.get(0));
            assertEquals(r2, snap.get(1));
            assertEquals(r3, snap.get(2));
        }

        @Test
        @DisplayName("快照是不可变列表")
        void snapshotIsImmutable() {
            TransitionRingBuffer<TestState> buf = new TransitionRingBuffer<>(4);
            buf.append(record(TestState.A, TestState.B));

            List<TransitionRecord<TestState>> snap = buf.snapshot();
            assertThrows(UnsupportedOperationException.class, () -> snap.add(record(TestState.B, TestState.C)));
        }
    }

    // ==================== 环形覆盖 ====================

    @Nested
    @DisplayName("环形覆盖")
    class RingOverwrite {

        @Test
        @DisplayName("容量为 4 时，追加 6 条后快照只保留最后 4 条")
        void overwriteKeepsLatest() {
            TransitionRingBuffer<TestState> buf = new TransitionRingBuffer<>(4);
            TransitionRecord<TestState> r0 = record(TestState.A, TestState.B);
            TransitionRecord<TestState> r1 = record(TestState.B, TestState.C);
            TransitionRecord<TestState> r2 = record(TestState.C, TestState.A);
            TransitionRecord<TestState> r3 = record(TestState.A, TestState.B);
            TransitionRecord<TestState> r4 = record(TestState.B, TestState.C);
            TransitionRecord<TestState> r5 = record(TestState.C, TestState.A);

            buf.append(r0);
            buf.append(r1);
            buf.append(r2);
            buf.append(r3);
            buf.append(r4);
            buf.append(r5);

            assertEquals(6, buf.totalTransitions());

            List<TransitionRecord<TestState>> snap = buf.snapshot();
            assertEquals(4, snap.size());
            // 最旧的被覆盖，保留 r2, r3, r4, r5
            assertEquals(r2, snap.get(0));
            assertEquals(r3, snap.get(1));
            assertEquals(r4, snap.get(2));
            assertEquals(r5, snap.get(3));
        }

        @Test
        @DisplayName("覆盖后 latest 仍返回最新记录")
        void overwriteLatestCorrect() {
            TransitionRingBuffer<TestState> buf = new TransitionRingBuffer<>(2);
            buf.append(record(TestState.A, TestState.B));
            buf.append(record(TestState.B, TestState.C));
            buf.append(record(TestState.C, TestState.A));

            TransitionRecord<TestState> latest = buf.latest();
            assertEquals(TestState.C, latest.from());
            assertEquals(TestState.A, latest.to());
        }
    }

    // ==================== 容量对齐 ====================

    @Nested
    @DisplayName("容量 2 的幂对齐")
    class CapacityAlignment {

        @Test
        @DisplayName("容量 3 向上对齐到 4")
        void capacity3AlignedTo4() {
            TransitionRingBuffer<TestState> buf = new TransitionRingBuffer<>(3);
            // 追加 4 条不会越界
            buf.append(record(TestState.A, TestState.B));
            buf.append(record(TestState.B, TestState.C));
            buf.append(record(TestState.C, TestState.A));
            buf.append(record(TestState.A, TestState.B));

            assertEquals(4, buf.snapshot().size());
        }

        @Test
        @DisplayName("容量 5 向上对齐到 8")
        void capacity5AlignedTo8() {
            TransitionRingBuffer<TestState> buf = new TransitionRingBuffer<>(5);
            // 追加 8 条
            for (int i = 0; i < 8; i++) {
                buf.append(record(TestState.A, TestState.B));
            }
            assertEquals(8, buf.snapshot().size());
        }

        @Test
        @DisplayName("容量 1 对齐到 1")
        void capacity1Stays1() {
            TransitionRingBuffer<TestState> buf = new TransitionRingBuffer<>(1);
            buf.append(record(TestState.A, TestState.B));
            buf.append(record(TestState.B, TestState.C));

            // 容量 1，只保留最新一条
            assertEquals(1, buf.snapshot().size());
            assertEquals(TestState.B, buf.latest().from());
        }
    }

    // ==================== 边界条件 ====================

    @Nested
    @DisplayName("边界条件")
    class EdgeCases {

        @Test
        @DisplayName("容量 0 抛出 IllegalArgumentException")
        void capacityZeroThrows() {
            assertThrows(IllegalArgumentException.class, () -> new TransitionRingBuffer<>(0));
        }

        @Test
        @DisplayName("负容量抛出 IllegalArgumentException")
        void capacityNegativeThrows() {
            assertThrows(IllegalArgumentException.class, () -> new TransitionRingBuffer<>(-1));
        }
    }
}
