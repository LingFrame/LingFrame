package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CanaryConfigDTO {
    private Integer percent;        // 灰度比例 0-100
    private String canaryVersion;   // 灰度版本
}
