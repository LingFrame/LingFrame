package com.lingframe.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.dashboard.dto.DashboardOperationOutcomeDTO;
import com.lingframe.dashboard.storage.AuditStorage;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dashboard 控制操作审计记录器。
 * <p>
 * 审计只记录操作者标识、操作、目标和结果元数据，不记录访问令牌或业务参数。
 */
@Slf4j
public class DashboardAuditRecorder {

    private final AuditStorage auditStorage;
    private final ObjectMapper objectMapper;

    public DashboardAuditRecorder(AuditStorage auditStorage, ObjectMapper objectMapper) {
        this.auditStorage = auditStorage;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public void recordMutation(String target, String action, DashboardOperationOutcomeDTO outcome) {
        recordMutation(target, action, outcome, null);
    }

    public void recordMutation(String target, String action, DashboardOperationOutcomeDTO outcome,
            String policyRevision) {
        if (outcome == null) {
            recordFailure(target, action, "操作结果缺失");
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operator", "dashboard-control");
        detail.put("target", target);
        detail.put("policyRevision", policyRevision);
        detail.put("runtimeApplied", outcome.isRuntimeApplied());
        detail.put("persisted", outcome.isPersisted());
        detail.put("recoveryReady", outcome.isRecoveryReady());
        detail.put("backupReady", outcome.isBackupReady());
        detail.put("failureReason", outcome.getFailureReason());
        write(target, action, outcome.isRuntimeApplied() ? "SUCCESS" : "FAILED", detail);
    }

    public void recordFailure(String target, String action, String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operator", "dashboard-control");
        detail.put("target", target);
        detail.put("failureReason", reason);
        write(target, action, "FAILED", detail);
    }

    private void write(String target, String action, String result, Map<String, Object> detail) {
        if (auditStorage == null) {
            return;
        }
        try {
            auditStorage.saveAuditLog(target, action, objectMapper.writeValueAsString(detail), result);
        } catch (Exception e) {
            log.warn("Failed to persist Dashboard control audit: target={}, action={}", target, action, e);
        }
    }
}
