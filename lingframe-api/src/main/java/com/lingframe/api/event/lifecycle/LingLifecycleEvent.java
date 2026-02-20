package com.lingframe.api.event.lifecycle;

import com.lingframe.api.event.AbstractLingEvent;
import lombok.Getter;

/**
 * 单元生命周期事件基类
 */
@Getter
public abstract class LingLifecycleEvent extends AbstractLingEvent {
    private final String lingId;
    private final String version;

    public LingLifecycleEvent(String lingId, String version) {
        super();
        this.lingId = lingId;
        this.version = version;
    }

    @Override
    public String toString() {
        return super.toString() + " source=" + lingId + ":" + version;
    }
}
