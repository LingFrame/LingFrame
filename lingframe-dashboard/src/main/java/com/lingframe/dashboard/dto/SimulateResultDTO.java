package com.lingframe.dashboard.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import com.lingframe.core.model.EngineTrace;

@Data
@Builder(toBuilder = true)
public class SimulateResultDTO {
    private String traceId;
    private String lingId;
    private String targetLingId;
    private String resourceType;
    private boolean allowed;
    private String message;
    private String ruleSource; // 规则来源 (e.g. "Patch", "Annotation")
    private boolean devModeBypass; // 是否因开发模式豁免
    private long timestamp;
    private List<EngineTrace> traces; // 干跑决策路径追踪
}
