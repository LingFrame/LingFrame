package com.lingframe.core.event;

import com.lingframe.api.event.LingEvent;
import com.lingframe.core.fsm.InstanceStatus;

import java.util.Objects;

/**
 * 实例状态变更事件。
 * 由实例状态协调器在每次状态转换成功后发布。
 * <p>
 * {@code instanceId} 是运行时快照的唯一键；{@code version} 仅作展示与运维检索，
 * 不得再作为“同版本至多一个实例”的隐含假设。
 */
public class InstanceStateChangedEvent implements LingEvent {

    private final String lingId;
    private final String instanceId;
    private final String version;
    private final InstanceStatus fromStatus;
    private final InstanceStatus toStatus;
    private final long timestamp;

    public InstanceStateChangedEvent(String lingId,
            String instanceId,
            String version,
            InstanceStatus fromStatus,
            InstanceStatus toStatus) {
        this.lingId = Objects.requireNonNull(lingId, "lingId");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.version = version;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.timestamp = System.currentTimeMillis();
    }

    public String getLingId() {
        return lingId;
    }

    /**
     * 实例唯一身份，供 RuntimeCoordinator 快照键使用。
     */
    public String getInstanceId() {
        return instanceId;
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

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("InstanceStateChangedEvent{ling=%s, instanceId=%s, version=%s, %s -> %s}",
                lingId, instanceId, version, fromStatus, toStatus);
    }
}
