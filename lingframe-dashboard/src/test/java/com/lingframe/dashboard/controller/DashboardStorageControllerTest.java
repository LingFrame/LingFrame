package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.DashboardStorageStatusDTO;
import com.lingframe.dashboard.storage.DashboardPersistenceStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Dashboard 存储状态控制器测试")
class DashboardStorageControllerTest {

    @Test
    @DisplayName("状态接口返回持久化、恢复和备份事实")
    void shouldReturnStorageStatus() {
        DashboardPersistenceStatus status = new DashboardPersistenceStatus();
        status.markStorageConfigured(true);
        status.recordPersistenceSuccess();
        status.recordRecoverySuccess();
        status.recordBackupSuccess();

        DashboardStorageController controller = new DashboardStorageController(status);
        ApiResponse<DashboardStorageStatusDTO> response = controller.getStatus();

        assertTrue(response.isSuccess());
        assertTrue(response.getData().isPersisted());
        assertTrue(response.getData().isRecoveryReady());
        assertTrue(response.getData().isBackupReady());
    }
}
