package com.lingframe.dashboard.dto;

import com.lingframe.core.spi.LeakRiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeakRiskReportDTO {
    private String lingId;
    private String version;
    private LeakRiskLevel level;
    private String summary;
    private List<String> details;
    private String checker;
    private long timestamp;
}
