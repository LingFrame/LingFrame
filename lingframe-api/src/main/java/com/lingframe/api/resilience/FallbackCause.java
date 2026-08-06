package com.lingframe.api.resilience;

/**
 * 降级触发原因。
 */
public enum FallbackCause {
    /** 熔断器处于打开状态 */
    CIRCUIT_OPEN,
    /** 触发限流保护 */
    RATE_LIMITED,
    /** 舱壁（线程隔离池）已满 */
    BULKHEAD_FULL
}
