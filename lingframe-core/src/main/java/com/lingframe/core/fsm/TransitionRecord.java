package com.lingframe.core.fsm;

/**
 * 状态转换记录。
 * <p>
 * 不可变值对象，记录一次成功跃迁的快照信息，
 * 用于故障回溯、审计追踪和 Dashboard 时间线展示。
 *
 * @param <S> 状态枚举类型
 */
public final class TransitionRecord<S extends Enum<S>> {

    private final String contextId;
    private final S from;
    private final S to;
    private final long timestamp;

    public TransitionRecord(String contextId, S from, S to, long timestamp) {
        this.contextId = contextId;
        this.from = from;
        this.to = to;
        this.timestamp = timestamp;
    }

    public String contextId() {
        return contextId;
    }

    public S from() {
        return from;
    }

    public S to() {
        return to;
    }

    public long timestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("TransitionRecord{id='%s', %s → %s, ts=%d}", contextId, from, to, timestamp);
    }
}
