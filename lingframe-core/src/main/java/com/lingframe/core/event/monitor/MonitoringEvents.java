package com.lingframe.core.event.monitor;

import com.lingframe.api.event.LingEvent;
import com.lingframe.api.security.PermissionAuditResult;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 灵珑运行时组件发布的监控事件集合。
 */
public class MonitoringEvents {

    @Getter
    @RequiredArgsConstructor
    public static class TraceLogEvent implements LingEvent {
        private final String traceId;
        private final String lingId;
        private final String action;
        private final String type;
        private final int depth;
        private final long timestamp = System.currentTimeMillis();
    }

    @Getter
    public static class AuditLogEvent implements LingEvent {
        private final String traceId;
        private final String lingId;
        private final String principal;
        private final String action;
        private final String resource;
        private final String capability;
        private final String source;
        private final String ruleSource;
        private final PermissionAuditResult result;
        private final String failureReason;
        private final long costNanos;
        private final long timestamp;

        public AuditLogEvent(String traceId,
                String lingId,
                String principal,
                String action,
                String resource,
                String capability,
                String source,
                String ruleSource,
                PermissionAuditResult result,
                String failureReason,
                long costNanos) {
            this.traceId = traceId;
            this.lingId = lingId;
            this.principal = principal;
            this.action = action;
            this.resource = resource;
            this.capability = capability;
            this.source = source;
            this.ruleSource = ruleSource;
            this.result = result;
            this.failureReason = failureReason;
            this.costNanos = costNanos;
            this.timestamp = System.currentTimeMillis();
        }

        public AuditLogEvent(String traceId, String lingId, String action, String resource, boolean success, long cost) {
            this(traceId,
                    lingId,
                    null,
                    action,
                    resource,
                    null,
                    null,
                    null,
                    success ? PermissionAuditResult.ALLOWED : PermissionAuditResult.DENIED,
                    null,
                    cost);
        }

        public boolean isSuccess() {
            return result != null && result.isSuccess();
        }

        public long getCost() {
            return costNanos;
        }
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

    @Getter
    public static class AlertNotifyEvent implements LingEvent {
        private final String traceId;
        private final String level;
        private final String type;
        private final String lingId;
        private final String message;
        private final String source;
        private final String ruleSource;
        private final long timestamp;

        public AlertNotifyEvent(String level, String type, String lingId, String message) {
            this(null, level, type, lingId, message, null, null);
        }

        public AlertNotifyEvent(String traceId, String level, String type, String lingId, String message) {
            this(traceId, level, type, lingId, message, null, null);
        }

        public AlertNotifyEvent(
                String traceId,
                String level,
                String type,
                String lingId,
                String message,
                String source,
                String ruleSource) {
            this.traceId = traceId;
            this.level = level;
            this.type = type;
            this.lingId = lingId;
            this.message = message;
            this.source = source;
            this.ruleSource = ruleSource;
            this.timestamp = System.currentTimeMillis();
        }
    }

    @Getter
    public static class LeakDetectionEvent implements LingEvent {
        private final String lingId;
        private final String version;
        private final boolean collected;
        private final String message;
        private final String detectionMode;
        private final long triggerTimeMillis;
        private final long timestamp;

        public LeakDetectionEvent(String lingId, String version, boolean collected, String message) {
            this(lingId, version, collected, message, "UNKNOWN", System.currentTimeMillis());
        }

        public LeakDetectionEvent(String lingId,
                String version,
                boolean collected,
                String message,
                String detectionMode,
                long triggerTimeMillis) {
            this.lingId = lingId;
            this.version = version;
            this.collected = collected;
            this.message = message;
            this.detectionMode = detectionMode;
            this.triggerTimeMillis = triggerTimeMillis;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
