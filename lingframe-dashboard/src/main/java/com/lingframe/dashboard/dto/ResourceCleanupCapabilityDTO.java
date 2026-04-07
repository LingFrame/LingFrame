package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceCleanupCapabilityDTO {
    private String runtime;
    private int jdkVersion;
    private boolean threadTargetAccessible;
    private boolean threadAccessControlAccessible;
    private boolean accessControlContextAccessible;
    private boolean virtualThreadIntrospectionAvailable;
    private boolean driverManagerAccessible;
    private String summary;
    private long timestamp;
}
