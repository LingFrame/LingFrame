package com.lingframe.core.event;

import com.lingframe.api.event.LingEvent;
import com.lingframe.api.event.LingEventListener;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class EventBus {

    private final Map<Class<? extends LingEvent>, List<LingEventListener<? extends LingEvent>>> listeners =
            new ConcurrentHashMap<>();

    public <E extends LingEvent> void subscribe(Class<E> eventType, LingEventListener<E> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(listener);
    }

    public <E extends LingEvent> void publish(E event) {
        List<LingEventListener<? extends LingEvent>> eventListeners = listeners.get(event.getClass());
        if (eventListeners != null) {
            for (LingEventListener<? extends LingEvent> listener : eventListeners) {
                try {
                    // 检查事件类型是否匹配
                    if (listener.getClass().isAssignableFrom(event.getClass())) {
                        @SuppressWarnings("unchecked")
                        LingEventListener<E> castListener = (LingEventListener<E>) listener;
                        castListener.onEvent(event);
                    }
                } catch (Exception e) {
                    log.error("Error processing event", e);
                }
            }
        }
    }
}