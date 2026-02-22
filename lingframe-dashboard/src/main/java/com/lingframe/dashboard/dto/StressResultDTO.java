package com.lingframe.dashboard.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StressResultDTO {
    private String lingId;
    private int totalRequests;
    private int v1Requests;
    private int v2Requests;
    private double v1Percent;
    private double v2Percent;
}
