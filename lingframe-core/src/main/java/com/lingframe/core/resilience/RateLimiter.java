package com.lingframe.core.resilience;

/**
 * 限流器接口
 */
public interface RateLimiter {

    /**
     * 尝试获取许可
     * 
     * @return true if permitted, false otherwise
     */
    boolean tryAcquire();

    /**
     * 获取名称
     */
    String getName();
}
