package com.lingframe.core.event;

import com.lingframe.api.event.LingEvent;

/**
 * 实例销毁事件。
 * 由 InstanceCoordinator.tearDown() 发布，
 * ResourceManager 监听后执行资源回收。
 */
public class InstanceDestroyedEvent implements LingEvent {
    private final String lingId;
    private final String version;

    public InstanceDestroyedEvent(String lingId, String version) {
        this.lingId = lingId;
        this.version = version;
    }

    public String getLingId() {
        return lingId;
    }

    public String getVersion() {
        return version;
    }
}
