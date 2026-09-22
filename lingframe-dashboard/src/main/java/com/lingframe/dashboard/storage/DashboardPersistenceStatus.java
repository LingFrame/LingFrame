package com.lingframe.dashboard.storage;

import com.lingframe.dashboard.dto.DashboardOperationOutcomeDTO;
import com.lingframe.dashboard.dto.DashboardStorageStatusDTO;

/**
 * 记录 Dashboard 当前进程可见的持久化、恢复和备份结果。
 * <p>
 * 该状态是运行期事实快照，不替代 SQLite 中的业务配置历史；进程重启后需要重新由恢复器建立。
 */
public class DashboardPersistenceStatus {

    private volatile boolean storageConfigured;
    private volatile boolean persisted;
    private volatile long lastPersistenceAt;
    private volatile String lastPersistenceError;
    private volatile boolean recoveryReady;
    private volatile long lastRecoveryAt;
    private volatile String lastRecoveryError;
    private volatile boolean backupReady;
    private volatile long lastBackupAt;
    private volatile String lastBackupError;

    public void markStorageConfigured(boolean configured) {
        this.storageConfigured = configured;
        if (!configured) {
            persisted = false;
            recoveryReady = false;
            backupReady = false;
        }
    }

    public void recordPersistenceSuccess() {
        persisted = true;
        recoveryReady = true;
        lastPersistenceAt = System.currentTimeMillis();
        lastPersistenceError = null;
    }

    public void recordPersistenceFailure(String reason) {
        persisted = false;
        recoveryReady = false;
        lastPersistenceAt = System.currentTimeMillis();
        lastPersistenceError = reason;
    }

    public void recordRecoverySuccess() {
        recoveryReady = true;
        lastRecoveryAt = System.currentTimeMillis();
        lastRecoveryError = null;
    }

    public void recordRecoveryFailure(String reason) {
        recoveryReady = false;
        lastRecoveryAt = System.currentTimeMillis();
        lastRecoveryError = reason;
    }

    public void recordBackupSuccess() {
        backupReady = true;
        lastBackupAt = System.currentTimeMillis();
        lastBackupError = null;
    }

    public void recordBackupFailure(String reason) {
        backupReady = false;
        lastBackupAt = System.currentTimeMillis();
        lastBackupError = reason;
    }

    public DashboardOperationOutcomeDTO operationOutcome(boolean runtimeApplied, String failureReason) {
        String reason = failureReason;
        if (reason == null && !persisted) {
            reason = lastPersistenceError;
        }
        return DashboardOperationOutcomeDTO.builder()
                .runtimeApplied(runtimeApplied)
                .persisted(persisted)
                .recoveryReady(recoveryReady)
                .backupReady(backupReady)
                .failureReason(reason)
                .build();
    }

    public DashboardStorageStatusDTO snapshot() {
        return DashboardStorageStatusDTO.builder()
                .storageConfigured(storageConfigured)
                .persisted(persisted)
                .lastPersistenceAt(lastPersistenceAt)
                .lastPersistenceError(lastPersistenceError)
                .recoveryReady(recoveryReady)
                .lastRecoveryAt(lastRecoveryAt)
                .lastRecoveryError(lastRecoveryError)
                .backupReady(backupReady)
                .lastBackupAt(lastBackupAt)
                .lastBackupError(lastBackupError)
                .build();
    }
}
