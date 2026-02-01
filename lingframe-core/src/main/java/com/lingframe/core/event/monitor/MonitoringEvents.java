package com.lingframe.core.event.monitor;

import com.lingframe.api.event.LingEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 监控类事件集合
 */
public class MonitoringEvents {

    @Getter
    @RequiredArgsConstructor
    public static class TraceLogEvent implements LingEvent {
        private final String traceId;
        private final String pluginId; // 当前插件
        private final String action; // 操作描述 (如 "→ OrderService.create")
        private final String type; // IN (入站), OUT (出站/返回), ERROR
        private final int depth; // 调用深度
        private final long timestamp = System.currentTimeMillis();
    }

    @Getter
    @RequiredArgsConstructor
    public static class AuditLogEvent implements LingEvent {
        private final String traceId;
        private final String pluginId;
        private final String action;
        private final String resource;
        private final boolean success;
        private final long cost;
        private final long timestamp = System.currentTimeMillis();
    }

    @Getter
    @RequiredArgsConstructor
    public static class CircuitBreakerStateEvent implements LingEvent {
        private final String resourceId;
        private final String oldState;
        private final String newState;
        private final double failureRate;
        private final long timestamp = System.currentTimeMillis();
    }
}