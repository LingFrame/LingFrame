package com.lingframe.api.config;

import lombok.Builder;
import lombok.Getter;

import java.io.Serializable;

/**
 * 调用治理配置下发契约。
 * <p>
 * 面向灵核 / 控制面的统一下发视图——灵核不再同时改 {@link GovernancePolicy.InvocationPolicy}
 * 与 {@code LingRuntimeConfig} 两份配置，只描述「我要设什么」，治理内核决定写到哪、怎么同步。
 * <p>
 * 字段语义与 {@link GovernancePolicy.InvocationPolicy} 一一对应；
 * 所有字段可空，null 表示「不动」。
 */
@Getter
@Builder
public class InvocationConfigDTO implements Serializable {

    /** 调用超时（毫秒），null 表示不动 */
    private final Integer timeoutMs;
    /** 限流阈值（QPS），null 表示不动 */
    private final Integer rateLimitPerSecond;
    /** 最大并发线程数，null 表示不动 */
    private final Integer maxConcurrentThreads;
    /** 重试次数，null 表示不动 */
    private final Integer retryCount;
    /** 重试耗尽后的回退值（字符串形态），null 表示不动 */
    private final String fallbackValue;
    /** 每分钟 CPU 预算（毫秒），null 表示不动 */
    private final Integer cpuBudgetMsPerMinute;
    /** 内存预算（MB），null 表示不动 */
    private final Integer memoryBudgetMb;
}
