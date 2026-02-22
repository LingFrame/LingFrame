package com.lingframe.api.event.lifecycle;

/**
 * 安装完成事件
 * 场景：记录审计日志
 */
public class LingInstalledEvent extends LingLifecycleEvent {
    public LingInstalledEvent(String lingId, String version) {
        super(lingId, version);
    }
}
