package com.lingframe.core.routing;

import lombok.Getter;

/**
 * 契约提供方描述符。
 * <p>
 * 描述「哪个 lingId 以什么权重提供哪个契约」，
 * 是 L0 provider 级路由（{@code ContractProviderRoutingFilter}）的选择输入。
 * <p>
 * 不可变值对象，权重变更通过 {@code updateProviderWeight} 替换整个描述符实现。
 * <p>
 * 路由层只认 weight 和方法资格，不引用实现方身份（灵核/灵元）。
 * 身份在注册时沉淀为 weight 数值：灵核默认 weight=100，灵元默认 weight=0。
 * <p>
 * 迭代期版本区分：当同一灵元部署两个版本时，Provider 注册标识显式升级为
 * {@code lingId:version}（例如 {@code user-ling:1.0.0} 与 {@code user-ling:1.1.0}）。
 * 迭代完成并确认相变后，保留版本的 Provider 标识收敛回裸 {@code lingId}。
 */
@Getter
public class ProviderDescriptor {

    /** 契约 ID（裸契约名或短 ID，如 {@code com.example.UserService}） */
    private final String contractId;

    /** 提供方灵元/灵核 ID */
    private final String lingId;

    /**
     * 版本标识。
     * <p>
     * 迁移期：灵核为 {@code lingcore-app}，灵元为裸 {@code lingId}，version 可为 null。
     * 迭代期：标识为 {@code lingId:version}，version 不可为 null。
     * 迭代完成相变确认后，保留版本的标识收敛回裸 {@code lingId}。
     */
    private final String version;

    /** 权重 0-100，注册时携带的初始权重，可被 Dashboard 覆盖 */
    private final int weight;

    public ProviderDescriptor(String contractId, String lingId, int weight) {
        this(contractId, lingId, null, weight);
    }

    public ProviderDescriptor(String contractId, String lingId, String version, int weight) {
        this.contractId = contractId;
        this.lingId = lingId;
        this.version = version;
        this.weight = Math.max(0, Math.min(100, weight));
    }

    /**
     * 用新权重创建副本（Dashboard 下发权重覆盖时用）。
     */
    public ProviderDescriptor withWeight(int newWeight) {
        return new ProviderDescriptor(this.contractId, this.lingId, this.version, newWeight);
    }

    /**
     * 返回 Provider 的唯一路由键。
     * <p>
     * 迁移期：灵核为 {@code lingcore-app}，灵元为裸 {@code lingId}。
     * 迭代期：{@code lingId:version}。
     * <p>
     * 路由层使用此键在 {@code providerIndex} 中区分不同候选。
     *
     * @return 路由键；version 为 null 时返回裸 lingId
     */
    public String providerKey() {
        return version == null ? lingId : lingId + ":" + version;
    }
}
