package com.lingframe.core.audit;

import com.lingframe.api.security.PermissionAuditResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AuditManager 测试")
class AuditManagerTest {

    @Test
    @DisplayName("asyncRecord 完整参数不报错")
    void shouldAsyncRecordWithFullParams() {
        assertDoesNotThrow(() -> AuditManager.asyncRecord(
                "trace-001", "ling-a", "admin",
                PermissionAuditResult.ALLOWED, "read", "execute",
                "resource-1", null, 1000L));
    }

    @Test
    @DisplayName("asyncRecord 简化参数不报错")
    void shouldAsyncRecordWithSimpleParams() {
        assertDoesNotThrow(() -> AuditManager.asyncRecord(
                "trace-002", "ling-b", "execute", "service-1",
                new Object[]{"arg1"}, "result", 500L));
    }

    @Test
    @DisplayName("asyncRecord null 参数不报错")
    void shouldAsyncRecordWithNullParams() {
        assertDoesNotThrow(() -> AuditManager.asyncRecord(
                null, null, null,
                PermissionAuditResult.DENIED, null, null,
                null, "permission denied", 0L));
    }

    @Test
    @DisplayName("asyncRecord 简化参数 null result 为 DENIED")
    void shouldAsyncRecordWithNullResult() {
        assertDoesNotThrow(() -> AuditManager.asyncRecord(
                "trace-003", "ling-c", "read", "res",
                null, null, 0L));
    }

    @Test
    @DisplayName("asyncRecord 长字符串不报错")
    void shouldAsyncRecordWithLongStrings() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("x");
        }
        String longStr = sb.toString();

        assertDoesNotThrow(() -> AuditManager.asyncRecord(
                "trace-004", longStr, longStr,
                PermissionAuditResult.ALLOWED, longStr, longStr,
                longStr, longStr, 999L));
    }

    @Test
    @DisplayName("asyncRecord 空字符串不报错")
    void shouldAsyncRecordWithEmptyStrings() {
        assertDoesNotThrow(() -> AuditManager.asyncRecord(
                "", "", "",
                PermissionAuditResult.ALLOWED, "", "", "", "", 0L));
    }
}
