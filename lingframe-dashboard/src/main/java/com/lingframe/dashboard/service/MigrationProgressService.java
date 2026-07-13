package com.lingframe.dashboard.service;

import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.ling.ProviderKind;
import com.lingframe.core.metrics.ProviderMetricsCollector;
import com.lingframe.core.metrics.ProviderMetricsCollector.ProviderStats;
import com.lingframe.dashboard.dto.ContractMigrationProgressDTO;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 迁移进度服务。
 * <p>
 * 聚合 {@link ProviderMetricsCollector} 的调用统计，
 * 按「灵核 vs 灵元」维度展示流量迁移进度，辅助决策「灵核旧实现是否可下线」。
 */
@Slf4j
public class MigrationProgressService {

    private final ProviderMetricsCollector providerMetricsCollector;
    private final LingServiceRegistry lingServiceRegistry;

    public MigrationProgressService(ProviderMetricsCollector providerMetricsCollector,
                                    LingServiceRegistry lingServiceRegistry) {
        this.providerMetricsCollector = providerMetricsCollector;
        this.lingServiceRegistry = lingServiceRegistry;
    }

    /**
     * 查询所有契约的迁移进度。
     * <p>
     * 性能优化：用 groupingBy 一次性按 contractId 聚合所有 stats，
     * 避免每个契约都遍历整个 statsMap 造成 O(n²) 性能瓶颈。
     *
     * @return 迁移进度列表，按灵核流量占比升序（灵核 0 调用排最前）
     */
    public List<ContractMigrationProgressDTO> getAllProgress() {
        // 一次性按 contractId 分组：O(n) 遍历 + O(n) 聚合
        Map<String, List<ProviderStats>> statsByContract = providerMetricsCollector.getAllStats().stream()
                .collect(Collectors.groupingBy(ProviderStats::getContractId));

        List<ContractMigrationProgressDTO> list = new ArrayList<>(statsByContract.size());
        for (Map.Entry<String, List<ProviderStats>> entry : statsByContract.entrySet()) {
            list.add(buildProgress(entry.getKey(), entry.getValue()));
        }
        // 灵核 0 调用排最前，便于页面高亮「可下线」
        list.sort((a, b) -> Double.compare(a.getCoreTrafficRatio(), b.getCoreTrafficRatio()));
        return list;
    }

    /**
     * 查询某契约的迁移进度。
     */
    public ContractMigrationProgressDTO getProgress(String contractId) {
        return buildProgress(contractId, providerMetricsCollector.getStatsByContract(contractId));
    }

    /**
     * 聚合单个契约的 stats 列表为迁移进度 DTO。
     * <p>
     * 提取为私有方法供 {@link #getAllProgress()} 和 {@link #getProgress(String)} 复用，
     * 避免重复实现聚合逻辑。
     */
    private ContractMigrationProgressDTO buildProgress(String contractId, List<ProviderStats> stats) {
        // 防御性处理：null/空列表返回零值 DTO，避免 NPE
        List<ProviderStats> safeStats = stats != null ? stats : Collections.emptyList();

        long coreInvocations = 0;
        long lingInvocations = 0;
        long coreDurationTotal = 0;
        long lingDurationTotal = 0;
        long coreFailures = 0;
        long lingFailures = 0;
        int providerCount = 0;

        for (ProviderStats s : safeStats) {
            providerCount++;
            if (s.getKind() == ProviderKind.CORE) {
                coreInvocations += s.getTotalInvocations();
                coreDurationTotal += s.getTotalDurationMs();
                coreFailures += s.getFailureCount();
            } else {
                lingInvocations += s.getTotalInvocations();
                lingDurationTotal += s.getTotalDurationMs();
                lingFailures += s.getFailureCount();
            }
        }

        long total = coreInvocations + lingInvocations;
        double coreRatio = total > 0 ? (double) coreInvocations / total : 0;
        double lingRatio = total > 0 ? (double) lingInvocations / total : 0;
        double coreAvg = coreInvocations > 0 ? (double) coreDurationTotal / coreInvocations : 0;
        double lingAvg = lingInvocations > 0 ? (double) lingDurationTotal / lingInvocations : 0;

        return ContractMigrationProgressDTO.builder()
                .contractId(contractId)
                .coreInvocations(coreInvocations)
                .lingInvocations(lingInvocations)
                .totalInvocations(total)
                .coreTrafficRatio(coreRatio)
                .lingTrafficRatio(lingRatio)
                .coreAvgDurationMs(coreAvg)
                .lingAvgDurationMs(lingAvg)
                .coreFailures(coreFailures)
                .lingFailures(lingFailures)
                .coreStale(coreInvocations == 0 && lingInvocations > 0)
                .providerCount(providerCount)
                .build();
    }

    /**
     * 查询所有灵核 0 调用的契约（即灵核实现可下线的候选）。
     *
     * @return 灵核 stale 契约 ID 列表
     */
    public List<String> getStaleCoreContracts() {
        // 复用 getAllProgress 的 groupingBy 结果，避免重复 O(n²) 遍历
        return getAllProgress().stream()
                .filter(ContractMigrationProgressDTO::isCoreStale)
                .map(ContractMigrationProgressDTO::getContractId)
                .collect(Collectors.toList());
    }
}
