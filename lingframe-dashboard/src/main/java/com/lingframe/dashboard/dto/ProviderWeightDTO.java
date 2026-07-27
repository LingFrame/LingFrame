package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 契约提供方权重 DTO。
 * <p>
 * 描述「某个 lingId 以多少权重提供某个契约」，
 * 是 Dashboard 契约路由页面的最小展示单元。
 * <p>
 * 路由层不引用实现方身份（灵核/灵元），身份在注册时沉淀为 weight 数值。
 * Dashboard 仅通过 {@code LingCoreConstants.LINGCORE_LING_ID} 识别灵核 baseline，
 * 用于「一键回滚到灵核」等运维动作，不参与路由决策。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderWeightDTO {

    /** 提供方灵元/灵核 ID */
    private String lingId;

    /**
     * 版本标识；迭代期二元候选时非空（{@code lingId:version}），迁移期为 null。
     */
    private String version;

    /** 是否灵核 baseline（lingId == LingCoreConstants.LINGCORE_LING_ID） */
    private boolean coreBaseline;

    /** 注册时携带的初始权重 0-100 */
    private int registeredWeight;

    /** Dashboard 下发的运行期覆盖权重；null 表示未覆盖，走 registeredWeight */
    private Integer overrideWeight;

    /** 当前生效权重（overrideWeight 非空时取它，否则取 registeredWeight） */
    private int effectiveWeight;
}
