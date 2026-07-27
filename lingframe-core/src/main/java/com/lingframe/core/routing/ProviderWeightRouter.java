package com.lingframe.core.routing;

import com.lingframe.api.exception.RoutingArchitectureViolationException;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.ProviderWeightChangedEvent;
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
 *   <li>注册时携带的初始 weight（{@link ProviderDescriptor#getWeight}）</li>
 * </ol>
 * 路由层不引用实现方身份（灵核/灵元），身份在注册时沉淀为 weight 数值。
 * <p>
 * 线程安全：权重覆盖表使用 {@link ConcurrentHashMap}，支持 Dashboard 并发下发。
 * <p>
 * 二元候选硬约束：{@link #selectProvider} 入口校验 {@code candidates.size() > 2}，
 * 违例时抛出 {@link RoutingArchitectureViolationException}，
 * 立即终止调用链并触发强告警，绝不静默降级。
 */
public class ProviderWeightRouter {

    /** contractId → (providerKey → 权重)，Dashboard 运行期覆盖 */
    private final Map<String, Map<String, Integer>> providerWeights = new ConcurrentHashMap<>();

    /** 事件总线，权重变更后广播 {@link ProviderWeightChangedEvent} */
    private final EventBus eventBus;

    public ProviderWeightRouter() {
        this(null);
    }

    public ProviderWeightRouter(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * 设置 provider 权重（Dashboard 下发）。
     * <p>
     * 权重变更后通过 {@link EventBus} 广播 {@link ProviderWeightChangedEvent}，
     * 供 {@link MigrationStateHolder} 监听并推进迁移状态机。
     *
     * @param contractId  契约 ID
     * @param providerKey 提供方路由键（{@link ProviderDescriptor#providerKey()}）
     * @param weight      新权重 0-100
     */
    public void setProviderWeight(String contractId, String providerKey, int weight) {
        int clamped = Math.max(0, Math.min(100, weight));
        Integer oldWeight = getOverrideWeight(contractId, providerKey);
        providerWeights.computeIfAbsent(contractId, k -> new ConcurrentHashMap<>())
                .put(providerKey, clamped);

        if (eventBus != null && (oldWeight == null || oldWeight != clamped)) {
            eventBus.publish(new ProviderWeightChangedEvent(contractId, providerKey,
                    oldWeight != null ? oldWeight : 0, clamped));
        }
    }

    /**
     * 清除指定契约下某个 provider 的权重覆盖，回退到默认值。
     */
    public void clearProviderWeight(String contractId, String providerKey) {
        Map<String, Integer> contractMap = providerWeights.get(contractId);
        if (contractMap != null) {
            contractMap.remove(providerKey);
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
     * @param contractId  契约 ID
     * @param providerKey 提供方路由键
     * @return 覆盖权重；未配置返回 null（表示走注册时初始 weight）
     */
    public Integer getOverrideWeight(String contractId, String providerKey) {
        Map<String, Integer> contractMap = providerWeights.get(contractId);
        return contractMap != null ? contractMap.get(providerKey) : null;
    }

    /**
     * 按权重选择一个 provider。
     * <p>
     * 有效权重计算：Dashboard 覆盖 > 注册时初始 weight。
     * 候选为空返回 null；所有权重为 0 时兜底选第一个。
     * <p>
     * 二元候选硬约束：候选数超过 2 时抛出
     * {@link RoutingArchitectureViolationException}，
     * 立即终止调用链并触发强告警。
     *
     * @param candidates 候选提供方列表（同一 contractId）
     * @param ctx        调用上下文（预留，供未来基于 traceId 的粘性路由扩展）
     * @return 选中的提供方；候选为空返回 null
     * @throws RoutingArchitectureViolationException 当候选数 &gt; 2
     */
    public ProviderDescriptor selectProvider(List<ProviderDescriptor> candidates, InvocationContext ctx) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        // 二元候选硬约束：灵珑路由层只支持 ≤2 个候选 provider
        if (candidates.size() > 2) {
            throw new RoutingArchitectureViolationException(
                    "Routing layer accepts at most 2 candidate providers, got " + candidates.size()
                            + " for contractId=" + candidates.get(0).getContractId());
        }

        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        String contractId = candidates.get(0).getContractId();
        Map<String, Integer> overrides = providerWeights.get(contractId);

        // 计算有效权重：Dashboard 覆盖 > 注册时初始 weight
        int totalWeight = 0;
        int[] effectiveWeights = new int[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            ProviderDescriptor desc = candidates.get(i);
            Integer override = overrides != null ? overrides.get(desc.providerKey()) : null;
            int w = override != null ? override : desc.getWeight();
            effectiveWeights[i] = Math.max(0, w);
            totalWeight += effectiveWeights[i];
        }

        if (totalWeight == 0) {
            // 所有 provider 权重为 0，兜底选第一个
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
