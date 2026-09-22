package com.lingframe.dashboard.service;

import com.lingframe.core.metrics.ProviderMetricsCollector;
import com.lingframe.core.metrics.ProviderMetricsCollector.ProviderStats;
import com.lingframe.dashboard.dto.ContractRoutingEvidenceDTO;
import com.lingframe.dashboard.dto.ContractRoutingDTO;
import com.lingframe.dashboard.dto.ProviderRoutingFactDTO;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 契约路由观测服务。
 */
public class ContractRoutingEvidenceService {

    private final ContractRoutingService contractRoutingService;
    private final ProviderMetricsCollector providerMetricsCollector;

    public ContractRoutingEvidenceService(ContractRoutingService contractRoutingService,
            ProviderMetricsCollector providerMetricsCollector) {
        this.contractRoutingService = contractRoutingService;
        this.providerMetricsCollector = providerMetricsCollector;
    }

    /**
     * 查询生效策略及真实调用命中事实。
     *
     * @param contractId 契约 ID
     * @return 策略与命中事实
     */
    public ContractRoutingEvidenceDTO getEvidence(String contractId) {
        ContractRoutingDTO policy = contractRoutingService.getContractRouting(contractId);
        List<ProviderStats> stats = providerMetricsCollector == null
                ? Collections.<ProviderStats>emptyList()
                : providerMetricsCollector.getStatsByContract(contractId);
        long total = stats.stream().mapToLong(ProviderStats::getTotalInvocations).sum();
        List<ProviderRoutingFactDTO> facts = stats.stream()
                .map(stat -> toFact(stat, total))
                .collect(Collectors.toList());
        return ContractRoutingEvidenceDTO.builder()
                .effectivePolicy(policy)
                .totalInvocations(total)
                .routingFacts(facts)
                .build();
    }

    private ProviderRoutingFactDTO toFact(ProviderStats stat, long total) {
        double percent = total > 0 ? (double) stat.getTotalInvocations() * 100.0 / total : 0.0;
        return ProviderRoutingFactDTO.builder()
                .contractId(stat.getContractId())
                .lingId(stat.getLingId())
                .version(stat.getVersion())
                .instanceId(stat.getInstanceId())
                .policyRevision(stat.getPolicyRevision())
                .routingReason(stat.getRoutingReason())
                .totalInvocations(stat.getTotalInvocations())
                .successCount(stat.getSuccessCount())
                .failureCount(stat.getFailureCount())
                .actualPercent(percent)
                .avgDurationMs(stat.getAvgDurationMs())
                .build();
    }
}
