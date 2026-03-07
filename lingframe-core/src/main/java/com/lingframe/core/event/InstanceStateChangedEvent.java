package com.lingframe.core.event;

import com.lingframe.api.event.LingEvent;
import com.lingframe.core.fsm.InstanceStatus;

/**
 * 实例状态变更事件。
 * 由 {@link com.lingframe.core.fsm.InstanceCoordinator} 在每次状态转换成功后发布。
 * <p>
 * 用途：
 * 1. Dashboard 实时感知实例状态变化
 * 2. 监控告警信号源
 * 3. 状态审计追踪
 */
public class InstanceStateChangedEvent implements LingEvent {

    private final String lingId;
    private final String version;
    private final InstanceStatus fromStatus;
    private final InstanceStatus toStatus;

    public InstanceStateChangedEvent(String lingId, String version,
            InstanceStatus fromStatus, InstanceStatus toStatus) {
        this.lingId = lingId;
        this.version = version;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    public String getLingId() {
        return lingId;
    }

    public String getVersion() {
        return version;
    }

    public InstanceStatus getFromStatus() {
        return fromStatus;
    }

    public InstanceStatus getToStatus() {
        return toStatus;
    }

    @Override
    public String toString() {
        return String.format("InstanceStateChangedEvent{ling=%s, version=%s, %s -> %s}",
                lingId, version, fromStatus, toStatus);
    }
}
