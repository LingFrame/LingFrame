package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.ContractRoutingDTO;
import com.lingframe.dashboard.service.ContractRoutingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 契约路由策略控制器。
 * <p>
 * 提供 Dashboard「契约路由策略」页面的 REST 接口，支持：
 * <ul>
 *   <li>列出所有有多 provider 的契约</li>
 *   <li>查询某契约的 provider 列表 + 权重</li>
 *   <li>设置单个 provider 权重</li>
 *   <li>一键回滚到灵核 100%</li>
 * </ul>
 * 路径前缀遵循 Dashboard 模块约定：{@code /lingframe/dashboard/contract-routing}。
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/lingframe/dashboard/contract-routing")
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ContractRoutingController {

    private final ContractRoutingService contractRoutingService;

    /**
     * 列出所有有多 provider 的契约。
     * <p>
     * 单 provider 契约不展示在列表中——无路由策略可配置。
     */
    @GetMapping("/contracts")
    public ApiResponse<List<String>> listMultiProviderContracts() {
        try {
            return ApiResponse.ok(contractRoutingService.listMultiProviderContracts());
        } catch (Exception e) {
            log.error("Failed to list multi-provider contracts", e);
            return ApiResponse.error("获取契约列表失败: " + e.getMessage());
        }
    }

    /**
     * 查询某契约的 provider 列表 + 权重配置。
     */
    @GetMapping("/{contractId}")
    public ApiResponse<ContractRoutingDTO> getContractRouting(@PathVariable String contractId) {
        try {
            return ApiResponse.ok(contractRoutingService.getContractRouting(contractId));
        } catch (Exception e) {
            log.error("Failed to get contract routing for: {}", contractId, e);
            return ApiResponse.error("获取契约路由失败: " + e.getMessage());
        }
    }

    /**
     * 设置某契约下指定 provider 的权重。
     * <p>
     * 请求体格式：{@code {"lingId": "user-ling", "weight": 30}}
     */
    @PostMapping("/{contractId}/weight")
    public ApiResponse<ContractRoutingDTO> setProviderWeight(
            @PathVariable String contractId,
            @RequestBody Map<String, Object> body) {
        try {
            String lingId = (String) body.get("lingId");
            Object weightObj = body.get("weight");
            if (lingId == null || lingId.isEmpty()) {
                return ApiResponse.error("lingId 不能为空");
            }
            if (weightObj == null) {
                return ApiResponse.error("weight 不能为空");
            }
            int weight = Integer.parseInt(String.valueOf(weightObj));
            // Controller 层显式校验范围：避免依赖 Service 静默 clamp 导致调用方困惑
            // （前端 saveProviderWeight 已做同样校验，此处为 API 契约防御）
            if (weight < 0 || weight > 100) {
                return ApiResponse.error("weight 必须是 0-100 的整数");
            }
            contractRoutingService.setProviderWeight(contractId, lingId, weight);
            return ApiResponse.ok("权重已更新", contractRoutingService.getContractRouting(contractId));
        } catch (NumberFormatException e) {
            return ApiResponse.error("weight 必须是 0-100 的整数");
        } catch (Exception e) {
            log.error("Failed to set provider weight for: {}", contractId, e);
            return ApiResponse.error("权重更新失败: " + e.getMessage());
        }
    }

    /**
     * 一键回滚到灵核 100%。
     * <p>
     * 将该契约下所有 CORE provider 权重设为 100，所有 LING provider 权重设为 0。
     */
    @PostMapping("/{contractId}/rollback")
    public ApiResponse<ContractRoutingDTO> rollbackToCore(@PathVariable String contractId) {
        try {
            contractRoutingService.rollbackToCore(contractId);
            return ApiResponse.ok("已回滚到灵核 100%", contractRoutingService.getContractRouting(contractId));
        } catch (Exception e) {
            log.error("Failed to rollback to core for: {}", contractId, e);
            return ApiResponse.error("回滚失败: " + e.getMessage());
        }
    }
}
