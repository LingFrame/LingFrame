package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 服务调用结果 DTO
 * <p>
 * 用于前端服务演练场展示调用结果及治理追踪。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvokeResultDTO {

    /** 调用是否成功 */
    private boolean success;

    /** 返回值（成功时） */
    private Object result;

    /** 错误信息（失败时） */
    private String error;

    /** 调用耗时（毫秒） */
    private long durationMs;

    /** 实际路由到的版本（按比例路由模式下返回） */
    private String routedVersion;

    /**
     * 执行模式：NORMAL（真实业务副作用）/ SIMULATION（仅治理链，无业务副作用）。
     */
    private String executionMode;

    /**
     * 是否预期产生真实业务副作用。
     * NORMAL=true，SIMULATION=false，便于前端醒目标注。
     */
    private boolean sideEffects;

    /** 治理追踪 */
    private List<TraceEntry> traces;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TraceEntry {
        /** 来源组件 */
        private String source;
        /** 执行动作 */
        private String action;
        /** 结果类型：PASS / FAIL / WARN */
        private String type;
    }
}
