package com.lingframe.core.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrafficStatsDTO {
    private String pluginId;
    private long totalRequests;
    private long v1Requests;
    private long v2Requests;
    private double v1Percent;
    private double v2Percent;
    private long windowStartTime;
}
