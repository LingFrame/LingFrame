package com.lingframe.core.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CanaryConfigDTO {
    private Integer percent;        // 灰度比例 0-100
    private String canaryVersion;   // 灰度版本
}
