package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 契约路由整表发布请求。
 * <p>
 * 发布必须携带最近一次读取到的修订号；服务端以修订号做并发校验，
 * 不允许控制端在旧快照上静默覆盖其他控制端的新策略。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContractRoutingPublishRequest {

    /** 最近读取到的策略修订号。 */
    private String expectedRevision;

    /** 完整 provider 覆盖表；未列出的 provider 恢复注册时权重。 */
    private Map<String, Integer> weights;
}
