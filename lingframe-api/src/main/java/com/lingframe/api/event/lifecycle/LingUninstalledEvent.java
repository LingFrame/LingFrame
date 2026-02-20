package com.lingframe.api.event.lifecycle;

/**
 * 卸载完成事件
 * 场景：清理临时文件
 */
public class LingUninstalledEvent extends LingLifecycleEvent {
    public LingUninstalledEvent(String lingId) {
        super(lingId, null);
    }
}
