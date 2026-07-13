package com.lingframe.core.ling;

import lombok.Getter;

/**
 * 契约提供方描述符。
 * <p>
 * 描述「哪个 lingId 以什么权重提供哪个契约」，
 * 是 L0 provider 级路由（{@code ContractProviderRoutingFilter}）的选择输入。
 * <p>
 * 不可变值对象，权重变更通过 {@code updateProviderWeight} 替换整个描述符实现。
 */
@Getter
public class ProviderDescriptor {

    /** 契约 ID（裸契约名或短 ID，如 {@code com.example.UserService}） */
    private final String contractId;

    /** 提供方灵元/灵核 ID */
    private final String lingId;

    /** 提供方类型 */
    private final ProviderKind kind;

    /** 权重 0-100，注册时携带的初始权重，可被 Dashboard 覆盖 */
    private final int weight;

    public ProviderDescriptor(String contractId, String lingId, ProviderKind kind, int weight) {
        this.contractId = contractId;
        this.lingId = lingId;
        this.kind = kind;
        this.weight = Math.max(0, Math.min(100, weight));
    }

    /**
     * 用新权重创建副本（Dashboard 下发权重覆盖时用）。
     */
    public ProviderDescriptor withWeight(int newWeight) {
        return new ProviderDescriptor(this.contractId, this.lingId, this.kind, newWeight);
    }
}
