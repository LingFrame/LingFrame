package com.lingframe.dashboard.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LogStreamDTO {
    private String type;      // TRACE / AUDIT / ALERT / LEAK_DETECTION / STATE_CHANGE
    private String traceId;
    private String lingId;
    private String version;
    private String content;
    private String tag;       // 辅助标签 (OK, FAIL, IN, OUT, INFO, WARNING, ERROR, CRITICAL)
    private int depth;        // 缩进深度
    private long timestamp;
    private String level;     // 告警级别 (INFO, WARNING, ERROR, CRITICAL)
}