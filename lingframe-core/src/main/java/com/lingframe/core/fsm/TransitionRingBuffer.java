package com.lingframe.core.fsm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 固定容量的环形缓冲区，用于存储状态转换历史。
 * <p>
 * 设计约束：
 * <ul>
 *   <li>无锁写入：使用 {@link AtomicLong} + {@link AtomicReferenceArray} 保证多写者安全</li>
 *   <li>固定容量：满时覆盖最旧记录，无内存泄漏风险</li>
 *   <li>快照读取：{@link #snapshot()} 返回不可变列表，读写无竞争</li>
 * </ul>
 * <p>
 * 线程安全说明：写入端通过 {@link AtomicLong#getAndIncrement} 获取唯一序号，
 * 即使多写者并发追加也不会丢失更新；读取端通过 volatile 语义获取最新写入位置。
 *
 * @param <S> 状态枚举类型
 */
class TransitionRingBuffer<S extends Enum<S>> {

    private final AtomicReferenceArray<TransitionRecord<S>> ring;
    private final int capacity;
    private final AtomicLong sequence = new AtomicLong(0);

    @SuppressWarnings("unchecked")
    TransitionRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got: " + capacity);
        }
        // 容量强制为 2 的幂，便于位运算取模
        this.capacity = roundToPowerOfTwo(capacity);
        this.ring = new AtomicReferenceArray<>(this.capacity);
    }

    /**
     * 追加一条转换记录。满时覆盖最旧记录。
     * <p>
     * 多写者安全：通过 {@link AtomicLong#getAndIncrement()} 获取唯一递增序号，
     * 再写入对应槽位，避免「读-改-写」竞态导致的丢更新。
     */
    void append(TransitionRecord<S> record) {
        long seq = sequence.getAndIncrement();
        int index = (int) (seq & (capacity - 1));
        ring.set(index, record);
    }

    /**
     * 返回最近一条转换记录，无记录时返回 null。
     */
    TransitionRecord<S> latest() {
        long seq = sequence.get();
        if (seq == 0) {
            return null;
        }
        int index = (int) ((seq - 1) & (capacity - 1));
        return ring.get(index);
    }

    /**
     * 返回从旧到新的转换历史快照（不可变）。
     * <p>
     * 读取时对 sequence 做一次快照，保证返回的列表在时间上有序且一致。
     */
    List<TransitionRecord<S>> snapshot() {
        long seq = sequence.get();
        if (seq == 0) {
            return Collections.emptyList();
        }

        long count = Math.min(seq, capacity);
        List<TransitionRecord<S>> result = new ArrayList<>((int) count);

        // 最旧记录的起始位置
        long start = seq - count;
        for (long i = start; i < seq; i++) {
            int index = (int) (i & (capacity - 1));
            TransitionRecord<S> record = ring.get(index);
            if (record != null) {
                result.add(record);
            }
        }

        return Collections.unmodifiableList(result);
    }

    /**
     * 当前已记录的转换次数（含已覆盖的）
     */
    long totalTransitions() {
        return sequence.get();
    }

    private static int roundToPowerOfTwo(int n) {
        // 上界保护：超过 2^30 时截断，避免 << 1 溢出为负数
        if (n > (1 << 30)) {
            return 1 << 30;
        }
        int highest = Integer.highestOneBit(n);
        return highest == n ? n : highest << 1;
    }
}
