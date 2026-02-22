package com.lingframe.api.event.lifecycle;

/**
 * 停止完成事件
 * 场景：资源释放通知
 */
public class LingStoppedEvent extends LingLifecycleEvent {
    public LingStoppedEvent(String lingId, String version) {
        super(lingId, version);
    }
}