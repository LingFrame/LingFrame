package com.lingframe.dashboard.service;

import com.lingframe.core.classloader.SharedApiClassLoader;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.dashboard.dto.RuntimeGovernanceReadinessDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RuntimeDiagnosticsService 测试")
class RuntimeDiagnosticsServiceTest {

    @AfterEach
    void tearDown() {
        SharedApiClassLoader.resetInstance();
    }

    @Test
    @DisplayName("首次观测到 LIMITED 就绪度时应发送告警")
    void shouldPublishAlertWhenFirstObservedReadinessIsLimited() {
        EventBus eventBus = new EventBus();
        AtomicReference<MonitoringEvents.AlertNotifyEvent> captured = new AtomicReference<>();
        eventBus.subscribe("test-listener", MonitoringEvents.AlertNotifyEvent.class, captured::set);
        RuntimeDiagnosticsService service = new RuntimeDiagnosticsService(eventBus);
        SharedApiClassLoader.freezeBoundary();

        eventBus.publish(new MonitoringEvents.ResourceCleanupCapabilityEvent(
                "BasicResourceGuard",
                17,
                false,
                false,
                false,
                true,
                false,
                "jdk=17,target=false"));

        MonitoringEvents.AlertNotifyEvent event = awaitEvent(captured, Duration.ofSeconds(2));
        assertNotNull(event);
        assertEquals("RUNTIME_GOVERNANCE_READINESS", event.getType());
        assertTrue(event.getMessage().contains("Runtime governance is active"));
    }

    @Test
    @DisplayName("就绪度从 LIMITED 退化到 BLOCKED 时应再次发送告警")
    void shouldPublishAlertWhenReadinessDegradesFurther() {
        EventBus eventBus = new EventBus();
        AtomicReference<MonitoringEvents.AlertNotifyEvent> captured = new AtomicReference<>();
        eventBus.subscribe("test-listener", MonitoringEvents.AlertNotifyEvent.class, captured::set);
        RuntimeDiagnosticsService service = new RuntimeDiagnosticsService(eventBus);
        SharedApiClassLoader.freezeBoundary();

        eventBus.publish(new MonitoringEvents.ResourceCleanupCapabilityEvent(
                "BasicResourceGuard",
                17,
                false,
                false,
                false,
                true,
                false,
                "jdk=17,target=false"));
        MonitoringEvents.AlertNotifyEvent first = awaitEvent(captured, Duration.ofSeconds(2));
        assertNotNull(first);

        captured.set(null);
        SharedApiClassLoader.resetInstance();
        eventBus.publish(new MonitoringEvents.ResourceCleanupCapabilityEvent(
                "SpringBasicResourceGuard",
                17,
                false,
                false,
                false,
                true,
                false,
                "jdk=17,target=false"));

        MonitoringEvents.AlertNotifyEvent second = awaitEvent(captured, Duration.ofSeconds(2));
        assertNotNull(second);
        assertTrue(second.getMessage().contains("Shared API boundary is not frozen"));

        RuntimeGovernanceReadinessDTO readiness = service.getGovernanceReadiness();
        assertEquals("BLOCKED", readiness.getStatus());
    }

    @Test
    @DisplayName("就绪度从 LIMITED 恢复到 READY 时应发送恢复通知")
    void shouldPublishRecoveryNotificationWhenReadinessReturnsToReady() {
        EventBus eventBus = new EventBus();
        AtomicReference<MonitoringEvents.AlertNotifyEvent> captured = new AtomicReference<>();
        eventBus.subscribe("test-listener", MonitoringEvents.AlertNotifyEvent.class, captured::set);
        RuntimeDiagnosticsService service = new RuntimeDiagnosticsService(eventBus);
        SharedApiClassLoader.freezeBoundary();

        eventBus.publish(new MonitoringEvents.ResourceCleanupCapabilityEvent(
                "BasicResourceGuard",
                17,
                false,
                false,
                false,
                true,
                false,
                "jdk=17,target=false"));
        MonitoringEvents.AlertNotifyEvent first = awaitEvent(captured, Duration.ofSeconds(2));
        assertNotNull(first);
        assertEquals("WARNING", first.getLevel());

        captured.set(null);
        eventBus.publish(new MonitoringEvents.ResourceCleanupCapabilityEvent(
                "BasicResourceGuard",
                17,
                true,
                true,
                true,
                true,
                true,
                "jdk=17,target=true"));

        MonitoringEvents.AlertNotifyEvent recovered = awaitEvent(captured, Duration.ofSeconds(2));
        assertNotNull(recovered);
        assertEquals("INFO", recovered.getLevel());
        assertTrue(recovered.getMessage().contains("Runtime governance is aligned"));

        RuntimeGovernanceReadinessDTO readiness = service.getGovernanceReadiness();
        assertEquals("READY", readiness.getStatus());
    }

    private <T> T awaitEvent(AtomicReference<T> captured, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            T event = captured.get();
            if (event != null) {
                return event;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for event", e);
            }
        }
        return null;
    }
}
