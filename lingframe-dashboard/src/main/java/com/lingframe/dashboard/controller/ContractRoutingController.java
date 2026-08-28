package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.ContractRoutingDTO;
import com.lingframe.dashboard.dto.ContractStressStepDTO;
import com.lingframe.dashboard.service.ContractRoutingService;
import com.lingframe.dashboard.service.SimulateService;
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
 *   <li>契约级真实微内核流量演练步进</li>
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
    private final SimulateService simulateService;

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
            return ApiResponse.error("获取契约列表失败", e);
        }
    }

    /**
     * 查询某契约的 provider 列表 + 权重配置。
     */
    @GetMapping("/{contractId:.+}")
    public ApiResponse<ContractRoutingDTO> getContractRouting(@PathVariable String contractId) {
        try {
            return ApiResponse.ok(contractRoutingService.getContractRouting(contractId));
        } catch (Exception e) {
            log.error("Failed to get contract routing for: {}", contractId, e);
            return ApiResponse.error("获取契约路由失败", e);
        }
    }

    /**
     * 设置某契约下指定 provider 的权重。
     * <p>
     * 请求体格式：{@code {"providerKey": "user-ling:1.1.0", "weight": 30}}<br>
     * {@code providerKey} 即路由键——灵元恒为 {@code lingId:version}（版本真源来自绑定实例上下文），
     * 灵核为裸 {@code lingcore-app}，与路由读路径键化一致；传错形会致权重落键错位、读路径静默丢失。
     */
    @PostMapping("/{contractId:.+}/weight")
    public ApiResponse<ContractRoutingDTO> setProviderWeight(
            @PathVariable String contractId,
            @RequestBody Map<String, Object> body) {
        try {
            String providerKey = (String) body.get("providerKey");
            Object weightObj = body.get("weight");
            if (providerKey == null || providerKey.isEmpty()) {
                return ApiResponse.error("providerKey 不能为空");
            }
            if (weightObj == null) {
                return ApiResponse.error("weight 不能为空");
            }
            int weight = Integer.parseInt(String.valueOf(weightObj));
            if (weight < 0 || weight > 100) {
                return ApiResponse.error("weight 必须是 0-100 的整数");
            }
            contractRoutingService.setProviderWeight(contractId, providerKey, weight);
            return ApiResponse.ok("权重已更新", contractRoutingService.getContractRouting(contractId));
        } catch (NumberFormatException e) {
            return ApiResponse.error("weight 必须是 0-100 的整数");
        } catch (Exception e) {
            log.error("Failed to set provider weight for: {}", contractId, e);
            return ApiResponse.error("权重更新失败", e);
        }
    }

    /**
     * 一键回滚到灵核 100%。
     * <p>
     * 将该契约下所有 CORE provider 权重设为 100，所有 LING provider 权重设为 0。
     */
    @PostMapping("/{contractId:.+}/rollback")
    public ApiResponse<ContractRoutingDTO> rollbackToCore(@PathVariable String contractId) {
        try {
            contractRoutingService.rollbackToCore(contractId);
            return ApiResponse.ok("已回滚到灵核 100%", contractRoutingService.getContractRouting(contractId));
        } catch (Exception e) {
            log.error("Failed to rollback to core for: {}", contractId, e);
            return ApiResponse.error("回滚失败", e);
        }
    }

    /**
     * 对指定契约执行单次真实微内核流量演练步进（穿透真实 Pipeline + 实时推送 Trace 日志）。
     *
     * @param contractId 契约 ID
     * @param mode 演练模式（DRY_RUN: 路由干跑 / PENETRATION: 真实穿透）
     */
    @PostMapping("/{contractId:.+}/stress-step")
    public ApiResponse<ContractStressStepDTO> stressContractStep(
            @PathVariable String contractId,
            @RequestParam(name = "mode", defaultValue = "DRY_RUN") String mode) {
        try {
            log.info("[Contract Routing] Received drill step request: contractId={}, mode={}", contractId, mode);
            ContractStressStepDTO result = simulateService.stressContractStep(contractId, mode);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            log.error("Failed to stress contract step: contractId={}, mode={}", contractId, mode, e);
            return ApiResponse.error("契约流量演练单步失败", e);
        }
    }
}
