package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.ContractMigrationProgressDTO;
import com.lingframe.dashboard.service.MigrationProgressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 迁移进度看板控制器。
 * <p>
 * 提供 Dashboard「迁移进度看板」页面的 REST 接口，支持：
 * <ul>
 *   <li>查询所有契约的灵核/灵元流量分布</li>
 *   <li>查询某契约的详细迁移进度</li>
 *   <li>查询灵核 0 调用的契约（可下线候选）</li>
 * </ul>
 * 路径前缀：{@code /lingframe/dashboard/migration}。
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/lingframe/dashboard/migration")
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class MigrationProgressController {

    private final MigrationProgressService migrationProgressService;

    /**
     * 查询所有契约的迁移进度。
     * <p>
     * 按灵核流量占比升序排列，灵核 0 调用的契约排最前。
     */
    @GetMapping("/progress")
    public ApiResponse<List<ContractMigrationProgressDTO>> getAllProgress() {
        try {
            return ApiResponse.ok(migrationProgressService.getAllProgress());
        } catch (Exception e) {
            log.error("Failed to get migration progress", e);
            return ApiResponse.error("获取迁移进度失败: " + e.getMessage());
        }
    }

    /**
     * 查询某契约的详细迁移进度。
     */
    @GetMapping("/progress/{contractId}")
    public ApiResponse<ContractMigrationProgressDTO> getProgress(@PathVariable String contractId) {
        try {
            return ApiResponse.ok(migrationProgressService.getProgress(contractId));
        } catch (Exception e) {
            log.error("Failed to get progress for: {}", contractId, e);
            return ApiResponse.error("获取契约迁移进度失败: " + e.getMessage());
        }
    }

    /**
     * 查询所有灵核 0 调用的契约（灵核实现可下线候选）。
     * <p>
     * 返回的契约列表意味着灵核实现已不再承接流量，可考虑下线。
     */
    @GetMapping("/stale")
    public ApiResponse<List<String>> getStaleCoreContracts() {
        try {
            return ApiResponse.ok(migrationProgressService.getStaleCoreContracts());
        } catch (Exception e) {
            log.error("Failed to get stale core contracts", e);
            return ApiResponse.error("获取可下线契约失败: " + e.getMessage());
        }
    }
}
