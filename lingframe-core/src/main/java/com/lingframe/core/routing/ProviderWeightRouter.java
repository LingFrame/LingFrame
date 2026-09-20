package com.lingframe.core.routing;

import com.lingframe.api.event.lifecycle.LingUninstalledEvent;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.pipeline.InvocationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 线程安全：写入串行发布不可变快照，选路只读取一次覆盖表，不持有写锁。
 * <p>
 * 支持 N 元多候选按权重分流：支持任意 N 个候选 provider 概率分配。
 */
public class ProviderWeightRouter {

    private static final Logger log = LoggerFactory.getLogger(ProviderWeightRouter.class);

    /** 写侧唯一协调锁；锁内不调用业务、持久化或事件回调。 */
    private final Object weightWriteLock = new Object();

    /** 路由器代次防止重建后接受旧修订。 */
    private final String epoch = UUID.randomUUID().toString();

    /** 一次发布覆盖索引与空作用域修订，避免删除后重建造成修订复用。 */
    private volatile WeightState weightState = new WeightState(0, Collections.emptyMap());

    /** 只保留有覆盖的契约；空契约使用全局发布序号，不积累墓碑记录。 */
    private static final class WeightState {
        private final long sequence;
        private final Map<String, ProviderWeightSnapshot> contracts;

        private WeightState(long sequence, Map<String, ProviderWeightSnapshot> contracts) {
            this.sequence = sequence;
            this.contracts = Collections.unmodifiableMap(new HashMap<>(contracts));
        }
    }

    /** 记录上一次候选节点数量，仅在候选数量发生变化时打印 warn 告警，避免热路径日志打满 */
    private final Map<String, Integer> lastCandidateCount = new ConcurrentHashMap<>();

    /** 事件总线：仅用于监听灵元卸载事件清理权重覆盖条目（不再广播权重变更，见 setProviderWeight 注释） */
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
     * <b>不发布 {@link ProviderWeightChangedEvent}</b>：迁移阶段推进是显式编排动作，
     * 仅由外部 {@code confirmPhaseTransition} + 排空校验（drainOk）驱动，
     * 权重变更事件在系统中无消费者（死广播）。
     * 若未来需要「权重归零 → 自动推进」接线，再显式订阅本类事件重新引入。
     *
     * @param contractId  契约 ID
     * @param providerKey 提供方路由键（{@link ProviderDescriptor#providerKey()}）
     * @param weight      新权重 0-100
     */
    public void setProviderWeight(String contractId, String providerKey, int weight) {
        ProviderWeightSnapshot.requireKey(contractId, "contractId");
        ProviderWeightSnapshot.requireKey(providerKey, "providerKey");
        int clamped = Math.max(0, Math.min(100, weight));
        synchronized (weightWriteLock) {
            Map<String, Integer> weights = new HashMap<>(getOverrideWeights(contractId));
            weights.put(providerKey, clamped);
            publish(contractId, weights);
        }
    }

    /**
     * 查询一个契约的完整覆盖快照。
     * <p>
     * 空作用域不保留历史条目，其修订会随其他契约写入而失效；调用方须重读后决策。
     *
     * @param contractId 契约标识
     * @return 不可变快照，未配置时覆盖表为空
     * @throws IllegalArgumentException 契约标识为空
     */
    public ProviderWeightSnapshot getWeightSnapshot(String contractId) {
        ProviderWeightSnapshot.requireKey(contractId, "contractId");
        WeightState state = weightState;
        ProviderWeightSnapshot snapshot = state.contracts.get(contractId);
        return snapshot != null ? snapshot : new ProviderWeightSnapshot(contractId,
                revision(state.sequence, contractId), Collections.emptyMap());
    }

    /**
     * 校验修订后一次替换整个契约的覆盖表。
     * <p>
     * 未列出的键恢复注册默认值，空表清除全部覆盖；这不是接流白名单。
     * 权重必须在零到一百之间，不要求总和为一百。键须由注册描述符取得，
     * 本层不验证提供方是否已注册。多次旧 setter 调用仍是多次独立发布。
     *
     * @param contractId 契约标识
     * @param expectedRevision 最近查询到的修订标识
     * @param weights 完整覆盖表，调用期间不得并发修改
     * @return 已发布的快照
     * @throws IllegalArgumentException 标识或权重非法
     * @throws NullPointerException 覆盖表为空引用
     * @throws ConcurrentModificationException 修订失配，消息包含当前修订；不自动重试
     */
    public ProviderWeightSnapshot replaceProviderWeights(String contractId, String expectedRevision,
            Map<String, Integer> weights) {
        ProviderWeightSnapshot validated = new ProviderWeightSnapshot(contractId, expectedRevision, weights);
        synchronized (weightWriteLock) {
            ProviderWeightSnapshot current = getWeightSnapshot(contractId);
            if (!current.getRevision().equals(expectedRevision)) {
                throw new ConcurrentModificationException("Weight revision conflict for " + contractId
                        + "; current revision=" + current.getRevision());
            }
            return publish(contractId, validated.getWeights());
        }
    }

    /** 调用方必须持有写锁；快照构造完成后才切换读侧引用。 */
    private ProviderWeightSnapshot publish(String contractId, Map<String, Integer> weights) {
        WeightState current = weightState;
        long sequence = Math.incrementExact(current.sequence);
        ProviderWeightSnapshot snapshot = new ProviderWeightSnapshot(contractId, revision(sequence, contractId), weights);
        Map<String, ProviderWeightSnapshot> contracts = new HashMap<>(current.contracts);
        if (weights.isEmpty()) {
            contracts.remove(contractId);
        } else {
            contracts.put(contractId, snapshot);
        }
        weightState = new WeightState(sequence, contracts);
        return snapshot;
    }

    /** 不透明标识包含代次、发布序号与作用域，不能跨契约复用。 */
    private String revision(long sequence, String contractId) {
        return epoch + ":" + sequence + ":" + contractId;
    }

    /**
     * 清除指定契约下某个 provider 的权重覆盖，回退到默认值。
     */
    public void clearProviderWeight(String contractId, String providerKey) {
        ProviderWeightSnapshot.requireKey(contractId, "contractId");
        ProviderWeightSnapshot.requireKey(providerKey, "providerKey");
        synchronized (weightWriteLock) {
            Map<String, Integer> weights = new HashMap<>(getOverrideWeights(contractId));
            if (weights.remove(providerKey) != null) {
                publish(contractId, weights);
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
        synchronized (weightWriteLock) {
            // 固定索引遍历，单个契约只发布一次；跨契约不承诺事务。
            for (String contractId : weightState.contracts.keySet()) {
                Map<String, Integer> weights = new HashMap<>(getOverrideWeights(contractId));
                boolean changed = weights.keySet().removeIf(providerKey ->
                        lingId.equals(providerKey) || providerKey.startsWith(versionSeparator));
                if (changed) {
                    publish(contractId, weights);
                }
            }
        }
        // 同步清理 lastCandidateCount，防止卸载后长时间无请求导致 entry 内存残留
        // 采用保守策略，卸载灵元时直接清空所有 contractId 告警状态，下次有请求时会重新计数。
        // 代价极小（可能多打印一次告警），但能确保不会有僵尸 entry 残留。
        lastCandidateCount.clear();
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
        ProviderWeightSnapshot snapshot = weightState.contracts.get(contractId);
        return snapshot != null ? snapshot.getWeights().get(providerKey) : null;
    }

    /**
     * 查询指定契约下所有 provider 的运行期覆盖权重。
     *
     * @param contractId 契约 ID
     * @return 权重覆盖 Map，未配置返回空 Map
     */
    public Map<String, Integer> getOverrideWeights(String contractId) {
        if (contractId == null) {
            return Collections.emptyMap();
        }
        ProviderWeightSnapshot snapshot = weightState.contracts.get(contractId);
        return snapshot != null ? new HashMap<>(snapshot.getWeights()) : Collections.emptyMap();
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

        // 本次选路只消费一个不可变覆盖表，即使控制面在循环中途发布也不混读。
        ProviderWeightSnapshot snapshot = weightState.contracts.get(contractId);
        Map<String, Integer> overrides = snapshot != null ? snapshot.getWeights() : null;

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
