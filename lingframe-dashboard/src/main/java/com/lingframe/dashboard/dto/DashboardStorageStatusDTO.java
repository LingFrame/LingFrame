package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dashboard 本进程内 SQLite 持久化、启动恢复和备份事实。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStorageStatusDTO {

    private boolean storageConfigured;
    private boolean persisted;
    private long lastPersistenceAt;
    private String lastPersistenceError;
    private boolean recoveryReady;
    private long lastRecoveryAt;
    private String lastRecoveryError;
    private boolean backupReady;
    private long lastBackupAt;
    private String lastBackupError;
}
