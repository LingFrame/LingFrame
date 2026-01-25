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
    private long timestamp;
}
