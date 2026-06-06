package com.lingframe.dashboard.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 状态机转换历史记录 DTO。
 * <p>
 * 对应 {@link com.lingframe.core.fsm.TransitionRecord}，
 * 用于 Dashboard REST 接口返回状态转换时间线。
 */
@Data
@Builder
public class TransitionHistoryDTO {

    /**
     * 上下文标识（lingId）
     */
    private String contextId;

    /**
     * 源状态
     */
    private String from;

    /**
     * 目标状态
     */
    private String to;

    /**
     * 转换时间戳（毫秒）
     */
    private long timestamp;
}
