package com.lingframe.api.event.lifecycle;

/**
 * 启动完成事件
 * 场景：服务注册、监控上报
 */
public class LingStartedEvent extends LingLifecycleEvent {
    public LingStartedEvent(String lingId, String version) {
        super(lingId, version);
    }
}
