package com.lingframe.dashboard.dto;

import com.lingframe.core.metrics.MetricsSnapshot;
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
public class LingHealthViewDTO {
    private MetricsSnapshot summary;

    @Builder.Default
    private Map<String, MetricsSnapshot> versions = new LinkedHashMap<>();
}
