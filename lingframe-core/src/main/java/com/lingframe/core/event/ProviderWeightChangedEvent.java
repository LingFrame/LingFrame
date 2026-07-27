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
 *
 * @author lingframe
 */
@Getter
public class ProviderWeightChangedEvent implements LingEvent {

    /** 契约 ID */
    private final String contractId;

    /** Provider 路由键（lingId 或 lingId:version） */
    private final String providerKey;

    /** 变更前权重 */
    private final int oldWeight;

    /** 变更后权重 */
    private final int newWeight;

    public ProviderWeightChangedEvent(String contractId, String providerKey, int oldWeight, int newWeight) {
        this.contractId = contractId;
        this.providerKey = providerKey;
        this.oldWeight = oldWeight;
        this.newWeight = newWeight;
    }
}
