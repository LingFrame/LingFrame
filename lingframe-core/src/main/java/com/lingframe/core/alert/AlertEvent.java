package com.lingframe.core.alert;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class AlertEvent {
    private String alertId;
    private AlertLevel level;
    private AlertType type;
    private String lingId;
    private String version;
    private String message;
    private LocalDateTime timestamp;
    private Map<String, Object> details;
    
    public enum AlertLevel {
        INFO,
        WARNING,
        ERROR,
        CRITICAL
    }
    
    public enum AlertType {
        LING_STATUS_CHANGED,
        LING_UNHEALTHY,
        LING_HEALTH_RECOVERED,
        
        CANARY_ERROR_RATE_HIGH,
        CANARY_LATENCY_HIGH,
        CANARY_ROLLBACK_TRIGGERED,
        
        CIRCUIT_BREAKER_OPENED,
        CIRCUIT_BREAKER_HALF_OPEN,
        CIRCUIT_BREAKER_CLOSED,
        RATE_LIMITER_TRIGGERED,
        
        MEMORY_USAGE_HIGH,
        CPU_USAGE_HIGH,
        THREAD_COUNT_HIGH,
        METASPACE_USAGE_HIGH,
        
        LING_INSTALL_FAILED,
        LING_UNINSTALL_FAILED,
        LING_RELOAD_FAILED,
        LING_START_FAILED,
        LING_STOP_FAILED
    }
    
    public static AlertEvent info(AlertType type, String lingId, String message) {
        return AlertEvent.builder()
                .alertId(java.util.UUID.randomUUID().toString())
                .level(AlertLevel.INFO)
                .type(type)
                .lingId(lingId)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }
    
    public static AlertEvent warning(AlertType type, String lingId, String message) {
        return AlertEvent.builder()
                .alertId(java.util.UUID.randomUUID().toString())
                .level(AlertLevel.WARNING)
                .type(type)
                .lingId(lingId)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }
    
    public static AlertEvent error(AlertType type, String lingId, String message) {
        return AlertEvent.builder()
                .alertId(java.util.UUID.randomUUID().toString())
                .level(AlertLevel.ERROR)
                .type(type)
                .lingId(lingId)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }
    
    public static AlertEvent critical(AlertType type, String lingId, String message) {
        return AlertEvent.builder()
                .alertId(java.util.UUID.randomUUID().toString())
                .level(AlertLevel.CRITICAL)
                .type(type)
                .lingId(lingId)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
