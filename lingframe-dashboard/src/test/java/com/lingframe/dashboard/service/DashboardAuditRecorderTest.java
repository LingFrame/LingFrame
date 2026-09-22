package com.lingframe.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.dashboard.dto.DashboardOperationOutcomeDTO;
import com.lingframe.dashboard.storage.AuditStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("Dashboard 控制操作审计测试")
class DashboardAuditRecorderTest {

    @Test
    @DisplayName("变更审计应记录目标、操作者和结果事实")
    void shouldRecordMutationFacts() {
        AuditStorage storage = mock(AuditStorage.class);
        DashboardAuditRecorder recorder = new DashboardAuditRecorder(storage, new ObjectMapper());
        DashboardOperationOutcomeDTO outcome = DashboardOperationOutcomeDTO.builder()
                .runtimeApplied(true).persisted(false).recoveryReady(false).backupReady(false)
                .failureReason("persist failed").build();

        recorder.recordMutation("svc-a", "ROUTE_PUBLISH", outcome);

        verify(storage).saveAuditLog(eq("svc-a"), eq("ROUTE_PUBLISH"),
                contains("\"operator\":\"dashboard-control\""), eq("SUCCESS"));
    }

    @Test
    @DisplayName("失败审计应记录失败原因")
    void shouldRecordFailureReason() {
        AuditStorage storage = mock(AuditStorage.class);
        DashboardAuditRecorder recorder = new DashboardAuditRecorder(storage, new ObjectMapper());

        recorder.recordFailure("ling-1", "GOVERNANCE_UPDATE", "revision conflict");

        verify(storage).saveAuditLog(eq("ling-1"), eq("GOVERNANCE_UPDATE"),
                contains("revision conflict"), eq("FAILED"));
    }
}
