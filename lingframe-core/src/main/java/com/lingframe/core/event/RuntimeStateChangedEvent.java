package com.lingframe.core.event;

import com.lingframe.api.event.LingEvent;
import com.lingframe.core.fsm.RuntimeStatus;

/**
 * 运行时状态变更事件。
 * <p>
 * 由 {@link com.lingframe.core.fsm.RuntimeCoordinator} 在宏观状态跃迁成功后发布，
 * 供 Dashboard、告警、审计等下游系统消费。
 */
public class RuntimeStateChangedEvent implements LingEvent {

    private final String lingId;
    private final RuntimeStatus from;
    private final RuntimeStatus to;
    private final long timestamp;

    public RuntimeStateChangedEvent(String lingId, RuntimeStatus from, RuntimeStatus to) {
        this.lingId = lingId;
        this.from = from;
        this.to = to;
        this.timestamp = System.currentTimeMillis();
    }

    public String getLingId() {
        return lingId;
    }

    public RuntimeStatus getFrom() {
        return from;
    }

    public RuntimeStatus getTo() {
        return to;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("RuntimeStateChanged{ling='%s', %s → %s, ts=%d}",
                lingId, from, to, timestamp);
    }
}