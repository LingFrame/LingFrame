package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.DashboardStorageStatusDTO;
import com.lingframe.dashboard.storage.DashboardPersistenceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard 持久化、恢复和备份状态查询控制器。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/lingframe/dashboard/storage")
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class DashboardStorageController {

    private final DashboardPersistenceStatus persistenceStatus;

    @GetMapping("/status")
    public ApiResponse<DashboardStorageStatusDTO> getStatus() {
        return ApiResponse.ok(persistenceStatus.snapshot());
    }
}
