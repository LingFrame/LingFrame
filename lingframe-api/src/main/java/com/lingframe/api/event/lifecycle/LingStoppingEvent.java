package com.lingframe.api.event.lifecycle;

/**
 * 停止前置事件
 * 场景：流量摘除、拒绝新请求
 */
public class LingStoppingEvent extends LingLifecycleEvent {
    public LingStoppingEvent(String lingId, String version) {
        super(lingId, version);
    }
}
