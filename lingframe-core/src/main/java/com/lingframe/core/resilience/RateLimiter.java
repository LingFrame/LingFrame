package com.lingframe.core.resilience;

/**
 * 限流器接口
 */
public interface RateLimiter {

    /**
     * 尝试获取许可
     * 
     * @return 获取到许可时返回 `true`，否则返回 `false`
     */
    boolean tryAcquire();

    /**
     * 获取名称
     */
    String getName();
}
