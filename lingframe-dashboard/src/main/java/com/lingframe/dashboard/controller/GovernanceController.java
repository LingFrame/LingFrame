package com.lingframe.dashboard.controller;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.ResourcePermissionDTO;
import com.lingframe.dashboard.service.DashboardService;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/lingframe/dashboard/governance")
@CrossOrigin(origins = "*") // 开发阶段允许跨域
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class GovernanceController {

    private final LocalGovernanceRegistry registry;
    private final DashboardService dashboardService;

    /**
     * 获取所有治理规则
     */
    @GetMapping("/rules")
    public ApiResponse<Map<String, GovernancePolicy>> getRules() {
        try {
            return ApiResponse.ok(registry.getAllPatches());
        } catch (Exception e) {
            log.error("Failed to get rules", e);
            return ApiResponse.error("获取规则失败: " + e.getMessage());
        }
    }

    /**
     * 获取指定单元的治理策略
     */
    @GetMapping("/{lingId}")
    public ApiResponse<GovernancePolicy> getPatch(@PathVariable String lingId) {
        try {
            GovernancePolicy policy = registry.getPatch(lingId);
            return ApiResponse.ok(policy);
        } catch (Exception e) {
            log.error("Failed to get patch for: {}", lingId, e);
            return ApiResponse.error("获取策略失败: " + e.getMessage());
        }
    }

    /**
     * 更新治理策略 (完整策略对象)
     */
    @PostMapping("/patch/{lingId}")
    public ApiResponse<GovernancePolicy> updatePatch(
            @PathVariable String lingId,
            @RequestBody GovernancePolicy policy) {
        try {
            registry.updatePatch(lingId, policy);
            return ApiResponse.ok("策略已更新", policy);
        } catch (Exception e) {
            log.error("Failed to update patch for: {}", lingId, e);
            return ApiResponse.error("策略更新失败: " + e.getMessage());
        }
    }

    /**
     * 更新资源权限 (简化接口，供 Dashboard 使用)
     * 调用 DashboardService 以使用 PermissionService.grant/revoke
     */
    @PostMapping("/{lingId}/permissions")
    public ApiResponse<ResourcePermissionDTO> updatePermissions(
            @PathVariable String lingId,
            @RequestBody ResourcePermissionDTO dto) {
        try {
            dashboardService.updatePermissions(lingId, dto);
            return ApiResponse.ok("权限已更新", dto);
        } catch (Exception e) {
            log.error("Failed to update permissions for: {}", lingId, e);
            return ApiResponse.error("权限更新失败: " + e.getMessage());
        }
    }
}
