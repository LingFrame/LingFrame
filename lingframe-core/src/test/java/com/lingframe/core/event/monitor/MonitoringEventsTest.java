package com.lingframe.core.event.monitor;

import com.lingframe.api.security.PermissionAuditResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MonitoringEvents 测试")
class MonitoringEventsTest {

    @Test
    @DisplayName("TraceLogEvent 构造和 getter")
    void shouldCreateTraceLogEvent() {
        MonitoringEvents.TraceLogEvent event = new MonitoringEvents.TraceLogEvent(
                "trace-1", "ling-a", "invoke", "call", 1);
        assertEquals("trace-1", event.getTraceId());
        assertEquals("ling-a", event.getLingId());
        assertEquals("invoke", event.getAction());
        assertEquals("call", event.getType());
        assertEquals(1, event.getDepth());
        assertTrue(event.getTimestamp() > 0);
    }

    @Test
    @DisplayName("AuditLogEvent 完整构造")
    void shouldCreateAuditLogEventFull() {
        MonitoringEvents.AuditLogEvent event = new MonitoringEvents.AuditLogEvent(
                "trace-1", "ling-a", "admin", "execute", "res-1",
                "cap-1", "src", "rule-src",
                PermissionAuditResult.ALLOWED, null, 1000L);
        assertEquals("trace-1", event.getTraceId());
        assertEquals("ling-a", event.getLingId());
        assertEquals("admin", event.getPrincipal());
        assertTrue(event.isSuccess());
        assertEquals(1000L, event.getCost());
    }

    @Test
    @DisplayName("AuditLogEvent 简化构造 - 成功")
    void shouldCreateAuditLogEventSimpleSuccess() {
        MonitoringEvents.AuditLogEvent event = new MonitoringEvents.AuditLogEvent(
                "trace-2", "ling-b", "read", "res-2", true, 500L);
        assertTrue(event.isSuccess());
        assertEquals(PermissionAuditResult.ALLOWED, event.getResult());
    }

    @Test
    @DisplayName("AuditLogEvent 简化构造 - 失败")
    void shouldCreateAuditLogEventSimpleFailure() {
        MonitoringEvents.AuditLogEvent event = new MonitoringEvents.AuditLogEvent(
                "trace-3", "ling-c", "write", "res-3", false, 200L);
        assertFalse(event.isSuccess());
        assertEquals(PermissionAuditResult.DENIED, event.getResult());
    }

    @Test
    @DisplayName("CircuitBreakerStateEvent 构造")
    void shouldCreateCircuitBreakerStateEvent() {
        MonitoringEvents.CircuitBreakerStateEvent event = new MonitoringEvents.CircuitBreakerStateEvent(
                "res-1", "CLOSED", "OPEN", 0.75);
        assertEquals("res-1", event.getResourceId());
        assertEquals("CLOSED", event.getOldState());
        assertEquals("OPEN", event.getNewState());
        assertEquals(0.75, event.getFailureRate(), 0.001);
    }

    @Test
    @DisplayName("AlertNotifyEvent 简化构造")
    void shouldCreateAlertNotifyEventSimple() {
        MonitoringEvents.AlertNotifyEvent event = new MonitoringEvents.AlertNotifyEvent(
                "WARN", "timeout", "ling-a", "service timeout");
        assertEquals("WARN", event.getLevel());
        assertEquals("timeout", event.getType());
        assertEquals("ling-a", event.getLingId());
        assertNull(event.getTraceId());
    }

    @Test
    @DisplayName("AlertNotifyEvent 带 traceId 构造")
    void shouldCreateAlertNotifyEventWithTraceId() {
        MonitoringEvents.AlertNotifyEvent event = new MonitoringEvents.AlertNotifyEvent(
                "trace-1", "ERROR", "circuit", "ling-b", "circuit open");
        assertEquals("trace-1", event.getTraceId());
        assertEquals("ERROR", event.getLevel());
    }

    @Test
    @DisplayName("AlertNotifyEvent 完整构造")
    void shouldCreateAlertNotifyEventFull() {
        MonitoringEvents.AlertNotifyEvent event = new MonitoringEvents.AlertNotifyEvent(
                "trace-2", "INFO", "health", "ling-c", "healthy", "src", "rule");
        assertEquals("src", event.getSource());
        assertEquals("rule", event.getRuleSource());
    }

    @Test
    @DisplayName("LeakDetectionEvent 简化构造")
    void shouldCreateLeakDetectionEventSimple() {
        MonitoringEvents.LeakDetectionEvent event = new MonitoringEvents.LeakDetectionEvent(
                "ling-a", "1.0.0", true, "collected");
        assertEquals("ling-a", event.getLingId());
        assertTrue(event.isCollected());
        assertEquals("UNKNOWN", event.getDetectionMode());
    }

    @Test
    @DisplayName("LeakDetectionEvent 完整构造")
    void shouldCreateLeakDetectionEventFull() {
        MonitoringEvents.LeakDetectionEvent event = new MonitoringEvents.LeakDetectionEvent(
                "ling-a", "2.0.0", false, "not collected", "AGGRESSIVE", 1000L);
        assertEquals("AGGRESSIVE", event.getDetectionMode());
        assertEquals(1000L, event.getTriggerTimeMillis());
    }

    @Test
    @DisplayName("ResourceCleanupCapabilityEvent 构造")
    void shouldCreateResourceCleanupCapabilityEvent() {
        MonitoringEvents.ResourceCleanupCapabilityEvent event = new MonitoringEvents.ResourceCleanupCapabilityEvent(
                "OpenJDK", 8, true, false, true, false, true, "summary");
        assertEquals("OpenJDK", event.getRuntime());
        assertEquals(8, event.getJdkVersion());
        assertTrue(event.isThreadTargetAccessible());
        assertFalse(event.isThreadAccessControlAccessible());
        assertEquals("summary", event.getSummary());
    }
}
