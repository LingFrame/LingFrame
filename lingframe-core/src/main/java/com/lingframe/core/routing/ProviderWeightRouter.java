package com.lingframe.core.routing;

import com.lingframe.api.event.lifecycle.LingUninstalledEvent;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.ProviderWeightChangedEvent;
import com.lingframe.core.pipeline.InvocationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
 * 支持 N 元多候选按权重分流：支持任意 N 个候选 provider 概率分配。
 */
public class ProviderWeightRouter {

    private static final Logger log = LoggerFactory.getLogger(ProviderWeightRouter.class);

    /** contractId → (providerKey → 权重)，Dashboard 运行期覆盖 */
    private final Map<String, Map<String, Integer>> providerWeights = new ConcurrentHashMap<>();

    /** 记录上一次候选节点数量，仅在候选数量发生变化时打印 warn 告警，避免热路径日志打满 */
    private final Map<String, Integer> lastCandidateCount = new ConcurrentHashMap<>();

    /** 事件总线，权重变更后广播 {@link ProviderWeightChangedEvent} */
    private final EventBus eventBus;

    public ProviderWeightRouter() {
        this(null);
    }

    public ProviderWeightRouter(EventBus eventBus) {
        this.eventBus = eventBus;
        // 监听灵元卸载事件，自动清理该灵元的权重覆盖条目，防止内存泄漏与重注册误用旧权重
        if (eventBus != null) {
            eventBus.subscribeGlobal(LingUninstalledEvent.class, this::onLingUninstalled);
        }
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
            // oldWeight 为 null 表示首次覆盖，传 null 让消费方区分「从未覆盖」与「覆盖前为 0」
            eventBus.publish(new ProviderWeightChangedEvent(contractId, providerKey, oldWeight, clamped));
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
     * 卸载清理：移除指定灵元在所有契约下的权重覆盖条目（含所有版本）。
     * <p>
     * 由 {@link LingUninstalledEvent} 监听自动触发，也可手动调用。
     * 匹配规则与 {@code MigrationStateHolder.matchesCandidate} 一致：
     * 裸 {@code lingId}（迁移期）或 {@code lingId:version}（迭代期）均清理；
     * 不用 {@code String.startsWith(lingId)} 避免前缀碰撞
     * （如 {@code user-ling} 误清 {@code user-ling-v2} 的无关条目）。
     *
     * @param lingId 被卸载灵元 ID
     */
    public void evictProvider(String lingId) {
        if (lingId == null) {
            return;
        }
        String versionSeparator = lingId + ":";
        for (String contractId : providerWeights.keySet()) {
            // compute 原子操作：与并发 setProviderWeight/clearProviderWeight 安全
            providerWeights.compute(contractId, (key, contractMap) -> {
                if (contractMap == null) {
                    return null;
                }
                contractMap.keySet().removeIf(providerKey ->
                        lingId.equals(providerKey) || providerKey.startsWith(versionSeparator));
                // 空 map 回收 entry，防内存泄漏（与 DefaultLingServiceRegistry.evictProvider 一致）
                return contractMap.isEmpty() ? null : contractMap;
            });
        }
    }

    /** 灵元卸载事件回调：自动清理该灵元的权重覆盖条目 */
    private void onLingUninstalled(LingUninstalledEvent event) {
        evictProvider(event.getLingId());
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
     * 按权重选择一个 provider（支持 N 元概率切流）。
     * <p>
     * 有效权重计算：Dashboard 覆盖 > 注册时初始 weight。
     * 候选为空返回 null；所有权重为 0 时兜底选第一个。
     *
     * @param candidates 候选提供方列表（同一 contractId）
     * @param ctx        调用上下文
     * @return 选中的提供方；候选为空返回 null
     */
    public ProviderDescriptor selectProvider(List<ProviderDescriptor> candidates, InvocationContext ctx) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        // 零分配快路径（Zero-Allocation Fast-Path）：常态下无对象创建，仅在极端异常遇到 null 元素时延迟分配
        boolean hasNull = false;
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i) == null) {
                hasNull = true;
                break;
            }
        }

        List<ProviderDescriptor> validCandidates = candidates;
        if (hasNull) {
            validCandidates = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                ProviderDescriptor c = candidates.get(i);
                if (c != null) {
                    validCandidates.add(c);
                }
            }
            if (validCandidates.isEmpty()) {
                return null;
            }
        }

        String contractId = validCandidates.get(0).getContractId();

        // 仅在候选节点数发生变化且超过 2 个时打印警告日志
        if (validCandidates.size() > 2) {
            Integer lastCount = lastCandidateCount.get(contractId);
            if (lastCount == null || lastCount != validCandidates.size()) {
                log.warn("Routing with {} candidates (>2) for contractId={}, treating as N-way weight split",
                        validCandidates.size(), contractId);
                lastCandidateCount.put(contractId, validCandidates.size());
            }
        } else if (lastCandidateCount.containsKey(contractId)) {
            // 候选数回落到 ≤2 时清状态，下次再超 2 会重新告警；
            // containsKey 守卫避免稳态二元路由（绝大多数请求）下对空 map 做写操作
            lastCandidateCount.remove(contractId);
        }

        if (validCandidates.size() == 1) {
            return validCandidates.get(0);
        }

        Map<String, Integer> overrides = providerWeights.get(contractId);

        // 计算有效权重：Dashboard 覆盖 > 注册时初始 weight
        int totalWeight = 0;
        int[] effectiveWeights = new int[validCandidates.size()];
        for (int i = 0; i < validCandidates.size(); i++) {
            ProviderDescriptor desc = validCandidates.get(i);
            Integer override = overrides != null ? overrides.get(desc.providerKey()) : null;
            int w = override != null ? override : desc.getWeight();
            effectiveWeights[i] = Math.max(0, w);
            totalWeight += effectiveWeights[i];
        }

        if (totalWeight == 0) {
            // 所有 provider 权重为 0，兜底选第一个
            return validCandidates.get(0);
        }

        int r = ThreadLocalRandom.current().nextInt(totalWeight);
        int cumulative = 0;
        for (int i = 0; i < validCandidates.size(); i++) {
            cumulative += effectiveWeights[i];
            if (r < cumulative) {
                return validCandidates.get(i);
            }
        }

        // 边界兜底
        return validCandidates.get(validCandidates.size() - 1);
    }
}
