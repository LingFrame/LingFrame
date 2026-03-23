package com.lingframe.core.alert;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class AlertManager {
    private final List<AlertChannel> channels = new CopyOnWriteArrayList<>();
    private final CircularBuffer<AlertEvent> alertHistory;
    
    public AlertManager() {
        this(100);
    }
    
    public AlertManager(int historySize) {
        this.alertHistory = new CircularBuffer<>(historySize);
    }
    
    public void registerChannel(AlertChannel channel) {
        channels.add(channel);
        log.info("[AlertManager] Registered alert channel: {}", channel.getName());
    }
    
    public void unregisterChannel(AlertChannel channel) {
        channels.remove(channel);
        log.info("[AlertManager] Unregistered alert channel: {}", channel.getName());
    }
    
    public void triggerAlert(AlertEvent event) {
        alertHistory.add(event);
        
        logAlert(event);
        
        for (AlertChannel channel : channels) {
            try {
                if (channel.shouldSend(event)) {
                    channel.send(event);
                }
            } catch (Exception e) {
                log.error("[AlertManager] Failed to send alert via channel: {}", channel.getName(), e);
            }
        }
    }
    
    private void logAlert(AlertEvent event) {
        String logMessage = String.format("[Alert] %s - %s: %s (Ling: %s)", 
                event.getLevel(), event.getType(), event.getMessage(), event.getLingId());
        
        switch (event.getLevel()) {
            case CRITICAL:
            case ERROR:
                log.error(logMessage);
                break;
            case WARNING:
                log.warn(logMessage);
                break;
            default:
                log.info(logMessage);
        }
    }
    
    public List<AlertEvent> getAlertHistory() {
        return alertHistory.toList();
    }
    
    public List<AlertEvent> getAlertsByLing(String lingId) {
        List<AlertEvent> result = new ArrayList<>();
        for (AlertEvent event : alertHistory.toList()) {
            if (lingId.equals(event.getLingId())) {
                result.add(event);
            }
        }
        return result;
    }
    
    public List<AlertEvent> getAlertsByLevel(AlertEvent.AlertLevel level) {
        List<AlertEvent> result = new ArrayList<>();
        for (AlertEvent event : alertHistory.toList()) {
            if (event.getLevel() == level) {
                result.add(event);
            }
        }
        return result;
    }
    
    public void clearHistory() {
        alertHistory.clear();
        log.info("[AlertManager] Alert history cleared");
    }
    
    private static class CircularBuffer<T> {
        private final Object[] buffer;
        private final int capacity;
        private int head = 0;
        private int size = 0;
        
        public CircularBuffer(int capacity) {
            this.capacity = capacity;
            this.buffer = new Object[capacity];
        }
        
        public synchronized void add(T item) {
            buffer[head] = item;
            head = (head + 1) % capacity;
            if (size < capacity) {
                size++;
            }
        }
        
        @SuppressWarnings("unchecked")
        public synchronized List<T> toList() {
            List<T> result = new ArrayList<>();
            if (size == 0) {
                return result;
            }
            
            int start = (head - size + capacity) % capacity;
            for (int i = 0; i < size; i++) {
                int index = (start + i) % capacity;
                result.add((T) buffer[index]);
            }
            
            return result;
        }
        
        public synchronized void clear() {
            head = 0;
            size = 0;
            for (int i = 0; i < capacity; i++) {
                buffer[i] = null;
            }
        }
    }
}
