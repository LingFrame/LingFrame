package com.lingframe.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 引擎追踪记录 (Engine Trace)
 * 用于记录 InvocationPipelineEngine 运行过程中的微观判定细节，如鉴权结果、路由决策等。
 * 是实现干跑推演（Dry-Run）和流量回放的核心诊断数据载体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EngineTrace implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 追踪来源，如：SecurityFilter, CanaryRouter, TerminalHandler
     */
    private String source;

    /**
     * 发生的动作或判定结果，如："Auth passed", "Hit 10% canary strategy"
     */
    private String action;

    /**
     * 追踪层级/类型，如：IN, OUT, INFO, WARN, ERROR, OK, FAIL, CANARY
     */
    private String type;

    /**
     * 追踪深度，用于在 Dashboard 渲染时序缩进
     */
    private int depth;

    /**
     * 发生时间戳
     */
    @Builder.Default
    private long timestamp = System.currentTimeMillis();

}
