package com.lingframe.core.plugin.event;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 插件运行时内部事件总线
 * 用于组件间解耦通信
 * <p>
 * 特点：
 * - 同步派发（保证顺序）
 * - 类型安全
 * - 轻量级
 */
@Slf4j
public class RuntimeEventBus {

    private final String pluginId;
    private final List<EventListener<?>> listeners = new CopyOnWriteArrayList<>();

    public RuntimeEventBus(String pluginId) {
        this.pluginId = pluginId;
    }

    /**
     * 订阅事件
     */
    public <E extends RuntimeEvent> Subscription subscribe(Class<E> eventType, Consumer<E> handler) {
        EventListener<E> listener = new EventListener<>(eventType, handler);
        listeners.add(listener);
        log.debug("[{}] Subscribed to {}", pluginId, eventType.getSimpleName());
        return () -> listeners.remove(listener);
    }

    /**
     * 发布事件
     */
    @SuppressWarnings("unchecked")
    public void publish(RuntimeEvent event) {
        log.debug("[{}] Publishing event: {}", pluginId, event.getClass().getSimpleName());

        for (EventListener<?> listener : listeners) {
            if (listener.eventType.isInstance(event)) {
                try {
                    ((EventListener<RuntimeEvent>) listener).handler.accept(event);
                } catch (Exception e) {
                    log.error("[{}] Error handling event {}: {}",
                            pluginId, event.getClass().getSimpleName(), e.getMessage(), e);
                }
            }
        }
    }

    /**
     * 清除所有订阅
     */
    public void clear() {
        listeners.clear();
        log.debug("[{}] All subscriptions cleared", pluginId);
    }

    /**
     * 获取订阅数量
     */
    public int getSubscriptionCount() {
        return listeners.size();
    }

    // ===== 内部类 =====

    /**
     * 订阅句柄（用于取消订阅）
     */
    @FunctionalInterface
    public interface Subscription {
        void unsubscribe();
    }

    @Value
    public class EventListener<E extends RuntimeEvent> {
        Class<E> eventType;
        Consumer<E> handler;

        // 可以添加便利方法
        public void accept(E event) {
            handler.accept(event);
        }

        public boolean supports(Class<?> type) {
            return eventType.isAssignableFrom(type);
        }
    }
}