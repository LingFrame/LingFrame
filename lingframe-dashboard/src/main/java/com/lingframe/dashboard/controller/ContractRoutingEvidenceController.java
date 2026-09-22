package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.ContractRoutingEvidenceDTO;
import com.lingframe.dashboard.service.ContractRoutingEvidenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 契约路由实际命中事实查询控制器。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/lingframe/dashboard/contract-routing")
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ContractRoutingEvidenceController {

    private final ContractRoutingEvidenceService evidenceService;

    @GetMapping("/{contractId:.+}/evidence")
    public ApiResponse<ContractRoutingEvidenceDTO> getEvidence(@PathVariable String contractId) {
        try {
            return ApiResponse.ok(evidenceService.getEvidence(contractId));
        } catch (Exception e) {
            log.error("Failed to get contract routing evidence: {}", contractId, e);
            return ApiResponse.error("获取契约路由命中事实失败", e);
        }
    }
}
