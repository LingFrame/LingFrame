package com.lingframe.dashboard.storage;

import com.lingframe.dashboard.dto.DashboardOperationOutcomeDTO;
import com.lingframe.dashboard.dto.DashboardStorageStatusDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Dashboard 持久化状态测试")
class DashboardPersistenceStatusTest {

    @Test
    @DisplayName("成功持久化和备份应分别进入可恢复状态")
    void shouldTrackPersistenceRecoveryAndBackup() {
        DashboardPersistenceStatus status = new DashboardPersistenceStatus();
        status.markStorageConfigured(true);
        status.recordPersistenceSuccess();
        status.recordRecoverySuccess();
        status.recordBackupSuccess();

        DashboardStorageStatusDTO snapshot = status.snapshot();
        assertTrue(snapshot.isStorageConfigured());
        assertTrue(snapshot.isPersisted());
        assertTrue(snapshot.isRecoveryReady());
        assertTrue(snapshot.isBackupReady());
        assertTrue(snapshot.getLastPersistenceAt() > 0);
        assertTrue(snapshot.getLastRecoveryAt() > 0);
        assertTrue(snapshot.getLastBackupAt() > 0);
    }

    @Test
    @DisplayName("持久化失败应阻止恢复就绪并保留原因")
    void shouldExposePersistenceFailure() {
        DashboardPersistenceStatus status = new DashboardPersistenceStatus();
        status.markStorageConfigured(true);
        status.recordPersistenceFailure("database unavailable");

        DashboardOperationOutcomeDTO outcome = status.operationOutcome(true, null);
        assertTrue(outcome.isRuntimeApplied());
        assertFalse(outcome.isPersisted());
        assertFalse(outcome.isRecoveryReady());
        assertFalse(outcome.isBackupReady());
        assertEquals("database unavailable", outcome.getFailureReason());
    }

    @Test
    @DisplayName("未配置存储时应清空耐久化事实")
    void shouldClearFactsWhenStorageDisabled() {
        DashboardPersistenceStatus status = new DashboardPersistenceStatus();
        status.markStorageConfigured(true);
        status.recordPersistenceSuccess();
        status.markStorageConfigured(false);

        DashboardStorageStatusDTO snapshot = status.snapshot();
        assertFalse(snapshot.isStorageConfigured());
        assertFalse(snapshot.isPersisted());
        assertFalse(snapshot.isRecoveryReady());
        assertFalse(snapshot.isBackupReady());
    }
}
