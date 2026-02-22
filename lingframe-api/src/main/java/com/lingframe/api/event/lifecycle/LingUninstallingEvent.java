package com.lingframe.api.event.lifecycle;

/**
 * 卸载前置事件 (可拦截)
 * 场景：防止误删核心单元
 */
public class LingUninstallingEvent extends LingLifecycleEvent {
    public LingUninstallingEvent(String lingId) {
        super(lingId, null); // 卸载时可能只知道 ID，Version 视上下文而定
    }
}
