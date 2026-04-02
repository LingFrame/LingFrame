package com.lingframe.dashboard.dto;

import com.lingframe.core.metrics.GovernanceMetricsSnapshot;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LingGovernanceMetricsViewDTO {
    private GovernanceMetricsSnapshot summary;

    @Builder.Default
    private Map<String, GovernanceMetricsSnapshot> versions = new LinkedHashMap<>();
}
