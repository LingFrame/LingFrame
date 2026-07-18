package com.lingframe.core.event;

import com.lingframe.api.event.LingEvent;

import java.util.Objects;

/**
 * 实例销毁事件。
 * 由 InstanceCoordinator.tearDown() 发布，
 * ResourceManager 监听后执行资源回收。
 * <p>
 * {@code instanceId} 用于快照收口；{@code version} 仍用于版本级资源前缀清理。
 */
public class InstanceDestroyedEvent implements LingEvent {
    private final String lingId;
    private final String instanceId;
    private final String version;
    private final long timestamp;

    public InstanceDestroyedEvent(String lingId, String instanceId, String version) {
        this.lingId = Objects.requireNonNull(lingId, "lingId");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.version = version;
        this.timestamp = System.currentTimeMillis();
    }

    public String getLingId() {
        return lingId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getVersion() {
        return version;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
