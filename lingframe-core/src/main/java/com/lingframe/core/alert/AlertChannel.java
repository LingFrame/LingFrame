package com.lingframe.core.alert;

public interface AlertChannel {
    String getName();
    
    boolean shouldSend(AlertEvent event);
    
    void send(AlertEvent event);
}
