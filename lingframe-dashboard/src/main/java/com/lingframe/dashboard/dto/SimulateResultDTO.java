package com.lingframe.dashboard.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder(toBuilder = true)
public class SimulateResultDTO {
    private String traceId;
    private String pluginId;
    private String targetPluginId;
    private String resourceType;
    private boolean allowed;
    private String message;
    private String ruleSource; // 规则来源 (e.g. "Patch", "Annotation")
    private boolean devModeBypass; // 是否因开发模式豁免
    private long timestamp;
}
