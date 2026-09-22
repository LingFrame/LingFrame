package com.lingframe.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.security.PermissionAuditResult;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.dashboard.storage.AuditStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("LogStreamService 审计持久化测试")
class LogStreamServiceAuditPersistenceTest {

    @Test
    @DisplayName("审计事件应持久化元数据且不包含业务参数")
    void shouldPersistAuditMetadata() throws Exception {
        AuditStorage storage = mock(AuditStorage.class);
        LogStreamService service = new LogStreamService(new EventBus(), storage, new ObjectMapper());
        MonitoringEvents.AuditLogEvent event = new MonitoringEvents.AuditLogEvent(
                "trace-1", "ling-1", "operator", "READ", "db:users", "storage:sql",
                "pipeline", "policy", PermissionAuditResult.DENIED, "access denied", 1000L);

        Method method = LogStreamService.class.getDeclaredMethod("handleAudit", MonitoringEvents.AuditLogEvent.class);
        method.setAccessible(true);
        method.invoke(service, event);

        verify(storage).saveAuditLog(eq("ling-1"), eq("READ"),
                contains("\"traceId\":\"trace-1\""), eq("DENIED"));
        service.destroy();
    }
}
