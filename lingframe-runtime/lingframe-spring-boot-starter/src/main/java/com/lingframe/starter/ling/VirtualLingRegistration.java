package com.lingframe.starter.ling;

import com.lingframe.core.ling.LingRuntimeConfig;
import lombok.Builder;
import lombok.Getter;

import java.util.Objects;

/**
 * 虚拟灵元声明式注册契约模型。
 * <p>
 * 在 Spring Boot 环境中，使用者可通过声明本类型的 {@code @Bean}，
 * 实现零样板代码的虚拟灵元统一发现与生命周期注册。
 * <p>
 * 示例：
 * <pre>{@code
 * @Bean
 * public VirtualLingRegistration seatunnelVirtualLing() {
 *     return VirtualLingRegistration.builder()
 *             .lingId("seatunnel")
 *             .rateLimitPerSecond(500)
 *             .circuitBreakerFailureRateThreshold(40)
 *             .build();
 * }
 * }</pre>
 */
@Getter
public class VirtualLingRegistration {

    private final String lingId;
    private final LingRuntimeConfig config;

    @Builder
    public VirtualLingRegistration(String lingId,
                                   LingRuntimeConfig config,
                                   Integer rateLimitPerSecond,
                                   Integer circuitBreakerFailureRateThreshold,
                                   Integer circuitBreakerSlidingWindowSize,
                                   Integer defaultTimeoutMs,
                                   Integer bulkheadMaxConcurrent) {
        this.lingId = Objects.requireNonNull(lingId, "lingId must not be null");

        if (config != null) {
            this.config = config;
        } else {
            LingRuntimeConfig.LingRuntimeConfigBuilder builder = LingRuntimeConfig.builder();
            if (rateLimitPerSecond != null) {
                builder.rateLimitPerSecond(rateLimitPerSecond);
            }
            if (circuitBreakerFailureRateThreshold != null) {
                builder.circuitBreakerFailureRateThreshold(circuitBreakerFailureRateThreshold);
            }
            if (circuitBreakerSlidingWindowSize != null) {
                builder.circuitBreakerSlidingWindowSize(circuitBreakerSlidingWindowSize);
            }
            if (defaultTimeoutMs != null) {
                builder.defaultTimeoutMs(defaultTimeoutMs);
            }
            if (bulkheadMaxConcurrent != null) {
                builder.bulkheadMaxConcurrent(bulkheadMaxConcurrent);
            }
            this.config = builder.build();
        }
    }

    /**
     * 创建使用默认治理配置的虚拟灵元声明。
     *
     * @param lingId 灵元唯一标识
     * @return 虚拟灵元声明对象
     */
    public static VirtualLingRegistration of(String lingId) {
        return of(lingId, LingRuntimeConfig.defaults());
    }

    /**
     * 创建指定治理配置的虚拟灵元声明。
     *
     * @param lingId 灵元唯一标识
     * @param config 运行时治理配置
     * @return 虚拟灵元声明对象
     */
    public static VirtualLingRegistration of(String lingId, LingRuntimeConfig config) {
        return new VirtualLingRegistration(lingId, config, null, null, null, null, null);
    }
}
