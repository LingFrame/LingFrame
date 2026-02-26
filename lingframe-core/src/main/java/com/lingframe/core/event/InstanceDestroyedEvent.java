package com.lingframe.core.event;

import java.util.EventObject;

/**
 * 当一个 LingInstance 在 FSM 状态机中运转到 TERMINATED（卸载完成）或者
 * 物理资源已被彻底消除时抛出的事件。供各种依赖于被动清理组件的观察者监听。
 */
public class InstanceDestroyedEvent extends EventObject {

    private static final long serialVersionUID = 1L;

    private final String pluginId;
    private final String version;

    public InstanceDestroyedEvent(Object source, String pluginId, String version) {
        super(source);
        this.pluginId = pluginId;
        this.version = version;
    }

    public String getPluginId() {
        return pluginId;
    }

    public String getVersion() {
        return version;
    }
}
