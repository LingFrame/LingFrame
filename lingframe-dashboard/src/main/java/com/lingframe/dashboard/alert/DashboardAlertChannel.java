package com.lingframe.dashboard.alert;

import com.lingframe.core.alert.AlertChannel;
import com.lingframe.core.alert.AlertEvent;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DashboardAlertChannel implements AlertChannel {
    
    @Override
    public String getName() {
        return "Dashboard";
    }
    
    @Override
    public boolean shouldSend(AlertEvent event) {
        return true;
    }
    
    @Override
    public void send(AlertEvent event) {
        log.info("[DashboardAlert] {} - {}: {}", event.getLevel(), event.getType(), event.getMessage());
    }
}
