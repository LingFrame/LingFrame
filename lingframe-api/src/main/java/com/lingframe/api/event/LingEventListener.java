package com.lingframe.api.event;

import com.lingframe.api.extension.ExtensionPoint;

/**
 * 事件监听器接口
 * <p>
 * 这是一个扩展点，插件可以通过实现此接口并标记 @Extension 来监听系统事件。
 * </p>
 * 
 * @param <E> 监听的事件类型
 * @author LingFrame
 */
public interface LingEventListener<E extends LingEvent> extends ExtensionPoint {
    
    /**
     * 处理事件
     * @param event 事件对象
     */
    void onEvent(E event);
}
