package com.lingframe.core.resilience;

/**
 * 熔断器接口
 */
public interface CircuitBreaker {

    /**
     * 是否允许请求通过
     */
    boolean tryAcquirePermission();

    /**
     * 记录成功
     */
    void onSuccess(long duration, java.util.concurrent.TimeUnit durationUnit);

    /**
     * 记录失败
     */
    void onError(long duration, java.util.concurrent.TimeUnit durationUnit, Throwable throwable);

    /**
     * 获取当前状态
     */
    State getState();

    enum State {
        CLOSED, // 关闭（正常）
        OPEN, // 打开（熔断）
        HALF_OPEN, // 半开（试探）
        FORCED_OPEN, // 强制打开
        DISABLED // 禁用（强制关闭）
    }
}
