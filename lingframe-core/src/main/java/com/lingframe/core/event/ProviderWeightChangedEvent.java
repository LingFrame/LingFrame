package com.lingframe.core.event;

import com.lingframe.api.event.LingEvent;
import com.lingframe.core.routing.MigrationStateHolder;
import com.lingframe.core.routing.ProviderWeightRouter;
import lombok.Getter;

/**
 * Provider 权重变更事件。
 * <p>
 * 由 {@link ProviderWeightRouter} 在权重下发变更后广播，
 * 供 {@link MigrationStateHolder} 监听并推进迁移状态机。
 * <p>
 * 事件语义：契约 {@code contractId} 下 {@code providerKey} 的权重
 * 从 {@code oldWeight} 变为 {@code newWeight}。
 * <p>
 * {@code oldWeight} 为 {@code Integer} nullable：当本次下发是首次覆盖（此前无 Dashboard 覆盖、
 * provider 走注册时初始 weight）时传 {@code null}，表示「无覆盖前值」；{@code newWeight} 恒非 null。
 * 消费方不应将 {@code null} 等同于 0——0 是 Dashboard 显式下发的覆盖权重，{@code null} 是「从未覆盖」。
 *
 * @author lingframe
 */
@Getter
public class ProviderWeightChangedEvent implements LingEvent {

    /** 契约 ID */
    private final String contractId;

    /** Provider 路由键（lingId 或 lingId:version） */
    private final String providerKey;

    /**
     * 变更前权重（覆盖语义）。
     * <p>
     * null 表示本次下发前该 provider 无 Dashboard 覆盖、走注册时初始 weight；
     * 非 null 表示此前已存在覆盖权重，值为覆盖前值（不是注册默认值，也不是生效权重）。
     */
    private final Integer oldWeight;

    /** 变更后权重 */
    private final int newWeight;

    public ProviderWeightChangedEvent(String contractId, String providerKey, Integer oldWeight, int newWeight) {
        this.contractId = contractId;
        this.providerKey = providerKey;
        this.oldWeight = oldWeight;
        this.newWeight = newWeight;
    }
}
