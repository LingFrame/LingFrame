package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 契约路由策略 DTO。
 * <p>
 * 描述「某个契约下所有提供方及其权重配置」，
 * 是 Dashboard 契约路由页面的主展示对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractRoutingDTO {

    /** 契约 ID（裸契约名或短 ID） */
    private String contractId;

    /** 提供方列表（含权重信息） */
    private List<ProviderWeightDTO> providers;

    /** 是否有多 provider（true 时页面展示权重配置 UI） */
    private boolean multiProvider;

    /** 灵核当前生效权重（便于页面高亮「流量在灵核」状态） */
    private int coreEffectiveWeight;

    /** 灵元当前生效权重之和 */
    private int lingEffectiveWeight;
}
