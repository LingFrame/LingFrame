package com.lingframe.core.metrics;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Provider 维度调用指标收集器。
 * <p>
 * 按 {@code contractId × lingId} 二维统计调用量和延迟，
 * 供 Dashboard 迁移进度看板查询流量分布。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>线程安全：使用 ConcurrentHashMap + AtomicLong</li>
 *   <li>低开销：仅计数，不做百分位计算（延迟只累加总和，p99 由外部采样）</li>
 *   <li>可清理：灵元卸载时 evict 对应 lingId 的指标</li>
 * </ul>
 */
@Slf4j
public class ProviderMetricsCollector {

    /** 二维 key：contractId + lingId */
    private final ConcurrentHashMap<String, ProviderStats> statsMap = new ConcurrentHashMap<>();

    /**
     * 记录一次 provider 调用。
     *
     * @param contractId 契约 ID
     * @param lingId     提供方灵元/灵核 ID
     * @param success    是否成功
     * @param durationMs 调用耗时（毫秒）
     */
    public void recordInvocation(String contractId, String lingId,
                                 boolean success, long durationMs) {
        if (contractId == null || lingId == null) {
            return;
        }
        String key = buildKey(contractId, lingId);
        ProviderStats stats = statsMap.computeIfAbsent(key, k -> new ProviderStats(contractId, lingId));
        stats.record(success, durationMs);
    }

    /**
     * 查询某契约下所有 provider 的调用统计。
     *
     * @param contractId 契约 ID
     * @return 统计列表（可能为空）
     */
    public List<ProviderStats> getStatsByContract(String contractId) {
        if (contractId == null) {
            return Collections.emptyList();
        }
        return statsMap.values().stream()
                .filter(s -> contractId.equals(s.contractId))
                .collect(Collectors.toList());
    }

    /**
     * 查询所有有调用记录的契约 ID。
     */
    public Set<String> getContractIds() {
        return statsMap.values().stream()
                .map(s -> s.contractId)
                .collect(Collectors.toSet());
    }

    /**
     * 查询所有 provider 的调用统计快照。
     * <p>
     * 用于一次性聚合场景（如 MigrationProgressService.getAllProgress），
     * 避免每个契约都遍历整个 statsMap 造成 O(n²) 性能瓶颈。
     *
     * @return 全量统计列表（可能为空）
     */
    public List<ProviderStats> getAllStats() {
        return new ArrayList<>(statsMap.values());
    }

    /**
     * 驱逐指定 lingId 的所有指标（灵元卸载时调用）。
     */
    public void evict(String lingId) {
        if (lingId == null) {
            return;
        }
        statsMap.entrySet().removeIf(e -> lingId.equals(e.getValue().lingId));
    }

    private String buildKey(String contractId, String lingId) {
        return contractId + "|" + lingId;
    }

    /**
     * 单个 (contractId, lingId) 维度的统计快照。
     */
    public static final class ProviderStats {
        private final String contractId;
        private final String lingId;
        private final AtomicLong totalInvocations = new AtomicLong();
        private final AtomicLong successCount = new AtomicLong();
        private final AtomicLong failureCount = new AtomicLong();
        private final AtomicLong totalDurationMs = new AtomicLong();

        ProviderStats(String contractId, String lingId) {
            this.contractId = contractId;
            this.lingId = lingId;
        }

        void record(boolean success, long durationMs) {
            totalInvocations.incrementAndGet();
            if (success) {
                successCount.incrementAndGet();
            } else {
                failureCount.incrementAndGet();
            }
            totalDurationMs.addAndGet(Math.max(0, durationMs));
        }

        public String getContractId() { return contractId; }
        public String getLingId() { return lingId; }
        public long getTotalInvocations() { return totalInvocations.get(); }
        public long getSuccessCount() { return successCount.get(); }
        public long getFailureCount() { return failureCount.get(); }
        public long getTotalDurationMs() { return totalDurationMs.get(); }

        /** 平均延迟（毫秒），无调用时返回 0 */
        public double getAvgDurationMs() {
            long total = totalInvocations.get();
            return total > 0 ? (double) totalDurationMs.get() / total : 0;
        }
    }
}
