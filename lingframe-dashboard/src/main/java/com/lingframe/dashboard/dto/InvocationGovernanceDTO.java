package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 调用治理配置 DTO。
 * 采用可空字段，便于表达“未覆盖，回退到静态定义或运行时默认值”。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvocationGovernanceDTO {
    private Integer timeoutMs;
    private Integer rateLimitPerSecond;
    private Integer maxConcurrentThreads;
    private Integer retryCount;
    private String fallbackValue;
}
