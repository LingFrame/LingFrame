package com.lingframe.dashboard.dto;

import com.lingframe.core.ling.ProviderKind;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 契约提供方权重 DTO。
 * <p>
 * 描述「某个 lingId 以什么类型、多少权重提供某个契约」，
 * 是 Dashboard 契约路由页面的最小展示单元。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderWeightDTO {

    /** 提供方灵元/灵核 ID */
    private String lingId;

    /** 提供方类型（CORE=灵核 / LING=灵元） */
    private ProviderKind kind;

    /** 注册时携带的初始权重 0-100 */
    private int registeredWeight;

    /** Dashboard 下发的运行期覆盖权重；null 表示未覆盖，走 registeredWeight */
    private Integer overrideWeight;

    /** 当前生效权重（overrideWeight 非空时取它，否则取 registeredWeight） */
    private int effectiveWeight;
}
