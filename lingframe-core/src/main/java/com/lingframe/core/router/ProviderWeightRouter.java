package com.lingframe.core.router;

import com.lingframe.core.ling.ProviderDescriptor;
import com.lingframe.core.ling.ProviderKind;
import com.lingframe.core.pipeline.InvocationContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * L0 provider 级权重路由器。
 * <p>
 * 在多 provider 场景下，按权重从候选提供方中选择一个，
 * 供 {@code ContractProviderRoutingFilter} 在 Pipeline 最早阶段完成 lingId 级选路。
 * <p>
 * 权重来源优先级：
 * <ol>
 *   <li>Dashboard 运行期覆盖（{@link #setProviderWeight}）</li>
 *   <li>默认值：CORE=100，LING=0（多 provider 默认不自动覆盖）</li>
 * </ol>
 * 只有 Dashboard 显式配置后，灵元 provider 才会承接流量。
 * <p>
 * 线程安全：权重覆盖表使用 {@link ConcurrentHashMap}，支持 Dashboard 并发下发。
 */
public class ProviderWeightRouter {

    /** contractId → (lingId → 权重)，Dashboard 运行期覆盖 */
    private final Map<String, Map<String, Integer>> providerWeights = new ConcurrentHashMap<>();

    /**
     * 设置 provider 权重（Dashboard 下发）。
     *
     * @param contractId 契约 ID
     * @param lingId     提供方灵元/灵核 ID
     * @param weight     新权重 0-100
     */
    public void setProviderWeight(String contractId, String lingId, int weight) {
        int clamped = Math.max(0, Math.min(100, weight));
        providerWeights.computeIfAbsent(contractId, k -> new ConcurrentHashMap<>())
                .put(lingId, clamped);
    }

    /**
     * 清除指定契约下某个 provider 的权重覆盖，回退到默认值。
     */
    public void clearProviderWeight(String contractId, String lingId) {
        Map<String, Integer> contractMap = providerWeights.get(contractId);
        if (contractMap != null) {
            contractMap.remove(lingId);
            if (contractMap.isEmpty()) {
                providerWeights.remove(contractId);
            }
        }
    }

    /**
     * 查询指定 provider 的运行期覆盖权重。
     * <p>
     * Dashboard 契约路由页面用此方法展示「当前已下发的权重」。
     *
     * @param contractId 契约 ID
     * @param lingId     提供方灵元/灵核 ID
     * @return 覆盖权重；未配置返回 null（表示走默认值）
     */
    public Integer getOverrideWeight(String contractId, String lingId) {
        Map<String, Integer> contractMap = providerWeights.get(contractId);
        return contractMap != null ? contractMap.get(lingId) : null;
    }

    /**
     * 按权重选择一个 provider。
     * <p>
     * 无 Dashboard 配置时使用默认值：CORE 默认 weight=100，LING 默认 weight=0，
     * 流量全部走灵核 baseline。有配置时按覆盖权重随机选。
     *
     * @param candidates 候选提供方列表（同一 contractId）
     * @param ctx        调用上下文（预留，供未来基于 traceId 的粘性路由扩展）
     * @return 选中的提供方；候选为空返回 null
     */
    public ProviderDescriptor selectProvider(List<ProviderDescriptor> candidates, InvocationContext ctx) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        String contractId = candidates.get(0).getContractId();
        Map<String, Integer> overrides = providerWeights.get(contractId);

        // 计算有效权重：Dashboard 覆盖 > 默认值
        int totalWeight = 0;
        int[] effectiveWeights = new int[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            ProviderDescriptor desc = candidates.get(i);
            // 单次 get 避免 containsKey + get 之间并发 remove 触发自动拆箱 NPE：
            // CHM 不允许 null value，get 返回 null 即视为未配置，回退到默认值
            Integer override = overrides != null ? overrides.get(desc.getLingId()) : null;
            int w = override != null
                    ? override
                    : (desc.getKind() == ProviderKind.CORE ? 100 : 0);
            effectiveWeights[i] = Math.max(0, w);
            totalWeight += effectiveWeights[i];
        }

        if (totalWeight == 0) {
            // 所有 provider 权重为 0（无 CORE 且 LING 全未配置），兜底选第一个
            return candidates.get(0);
        }

        int r = ThreadLocalRandom.current().nextInt(totalWeight);
        int cumulative = 0;
        for (int i = 0; i < candidates.size(); i++) {
            cumulative += effectiveWeights[i];
            if (r < cumulative) {
                return candidates.get(i);
            }
        }

        // 浮点/边界兜底，不可达
        return candidates.get(candidates.size() - 1);
    }
}
