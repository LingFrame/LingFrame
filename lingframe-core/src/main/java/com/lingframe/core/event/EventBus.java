package com.lingframe.core.event;

import com.lingframe.api.event.LingEvent;
import com.lingframe.api.event.LingEventListener;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class EventBus {

    private final Map<Class<? extends LingEvent>, List<ListenerWrapper>> listeners =
            new ConcurrentHashMap<>();

    // 包装器，记录监听器归属的单元ID
    @Value
    public static class ListenerWrapper {
        String lingId;
        LingEventListener<? extends LingEvent> listener;

        public String lingId() {
            return lingId;
        }

        public LingEventListener listener() {
            return listener;
        }
    }

    /**
     * 注册监听器
     */
    public <E extends LingEvent> void subscribe(String lingId, Class<E> eventType, LingEventListener<E> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(new ListenerWrapper(lingId, listener));
    }

    /**
     * 卸载单元时，强制移除该单元注册的所有监听器
     */
    public void unsubscribeAll(String lingId) {
        log.info("Cleaning up event listeners for ling: {}", lingId);
        for (List<ListenerWrapper> list : listeners.values()) {
            list.removeIf(wrapper -> {
                boolean match = wrapper.lingId().equals(lingId);
                if (match) {
                    log.debug("Removed listener: {}", wrapper.listener().getClass().getName());
                }
                return match;
            });
        }
    }

    public <E extends LingEvent> void publish(E event) {
        List<ListenerWrapper> wrappers = listeners.get(event.getClass());
        if (wrappers == null) return;
        for (ListenerWrapper wrapper : wrappers) {
            try {
                @SuppressWarnings("unchecked")
                LingEventListener<E> castListener = (LingEventListener<E>) wrapper.listener();
                if (castListener != null) {
                    castListener.onEvent(event);
                }
            } catch (RuntimeException e) {
                log.warn("Event listener threw exception, propagating: {}", e.getMessage());
                throw e; // Fail-Fast
            } catch (Exception e) {
                log.error("Error processing event", e);
            }
        }
    }
}