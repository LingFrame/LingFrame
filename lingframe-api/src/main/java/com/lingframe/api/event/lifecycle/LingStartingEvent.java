package com.lingframe.api.event.lifecycle;

/**
 * 启动前置事件 (可拦截)
 * 场景：License 校验、环境检查
 */
public class LingStartingEvent extends LingLifecycleEvent {
    public LingStartingEvent(String lingId, String version) {
        super(lingId, version);
    }
}
