package com.lingframe.dashboard.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LogStreamDTO {
    private String type;      // TRACE / AUDIT
    private String traceId;
    private String pluginId;
    private String content;
    private String tag;       // 辅助标签 (OK, FAIL, IN, OUT)
    private int depth;        // 缩进深度
    private long timestamp;
}