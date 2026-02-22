package com.lingframe.core.governance.resilience;

import com.lingframe.core.resilience.CircuitBreaker;
import com.lingframe.core.resilience.SlidingWindowCircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SlidingWindowCircuitBreakerTest {

    @Test
    @DisplayName("正常调用：应允许通过并保持 CLOSED 状态")
    void testNormalExecution() {
        // 构造参数：name, failRate(50%), slowRate(50%), slowDuration(100ms), windowSize(5),
        // minCalls(3), waitTime(1000ms)
        SlidingWindowCircuitBreaker breaker = new SlidingWindowCircuitBreaker("res", 50, 50, 100, 5, 3, 1000);

        assertTrue(breaker.tryAcquirePermission());
        breaker.onSuccess(10, TimeUnit.MILLISECONDS);

        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
    }

    @Test
    @DisplayName("错误率触发熔断：达到阈值应进入 OPEN 状态")
    void testTripToOpen() {
        // 阈值 50%，最小请求 3，窗口 5
        SlidingWindowCircuitBreaker breaker = new SlidingWindowCircuitBreaker("res", 50, 50, 100, 5, 3, 1000);

        breaker.onSuccess(10, TimeUnit.MILLISECONDS); // 成功 1
        breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException("fail")); // 失败 1
        breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException("fail")); // 失败 2
        // 此时发生 3 次，失败率 66% > 50%，触发熔断

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        assertFalse(breaker.tryAcquirePermission(), "处于 OPEN 状态时不应允许请求");
    }

    @Test
    @DisplayName("未达到最小请求量不熔断")
    void testNotTripBelowMinRequests() {
        // 最小需要 5 个请求才会计算阈值熔断
        SlidingWindowCircuitBreaker breaker = new SlidingWindowCircuitBreaker("res", 50, 50, 100, 10, 5, 1000);

        breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException("fail"));
        breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException("fail"));
        // 此时虽然 100% 失败率，但是没满足 5 次最小调用限制，应仍允许调用

        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertTrue(breaker.tryAcquirePermission());
    }

    @Test
    @DisplayName("慢调用触发熔断：达到慢调用阈值应进入 OPEN 状态")
    void testSlowCallTripToOpen() {
        // 阈值 50%，慢调用界限 100ms，最小请求 3
        SlidingWindowCircuitBreaker breaker = new SlidingWindowCircuitBreaker("res", 50, 50, 100, 5, 3, 1000);

        breaker.onSuccess(10, TimeUnit.MILLISECONDS); // 快 1
        breaker.onSuccess(150, TimeUnit.MILLISECONDS); // 慢 1
        breaker.onSuccess(200, TimeUnit.MILLISECONDS); // 慢 2
        // 总 3 次调用，慢调用率 66% > 50%，触发熔断

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        assertFalse(breaker.tryAcquirePermission());
    }

    @Test
    @DisplayName("半开状态恢复流转测试")
    void testHalfOpenRecovery() throws InterruptedException {
        // 等待时间设短 100ms
        SlidingWindowCircuitBreaker breaker = new SlidingWindowCircuitBreaker("res", 50, 50, 100, 5, 3, 100);

        breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException("fail"));
        breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException("fail"));
        breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException("fail")); // 触发熔断

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        // 睡过 wait 时间
        Thread.sleep(150);

        // 此时第一次 tryAcquirePermission() 会探测性放行，变为 HALF_OPEN
        assertTrue(breaker.tryAcquirePermission(), "应该允许探针请求");
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState());

        // 在 HALF_OPEN 状态下不断成功，直到 permittedNumberOfCallsInHalfOpenState (默认可能是10)
        // 源码写死了 permittedNumberOfCallsInHalfOpenState = 10，所以模拟 10 次成功
        for (int i = 0; i < 10; i++) {
            breaker.onSuccess(10, TimeUnit.MILLISECONDS);
        }

        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
    }

    @Test
    @DisplayName("半开状态失败应重新跌回熔断")
    void testHalfOpenFailBackToOpen() throws InterruptedException {
        SlidingWindowCircuitBreaker breaker = new SlidingWindowCircuitBreaker("res", 50, 50, 100, 5, 3, 100);

        breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException());
        breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException());
        breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException()); // 触发熔断
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        Thread.sleep(150); // 冷却

        assertTrue(breaker.tryAcquirePermission()); // 发起探测
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState());

        // 但这一次探测失败了
        breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException());

        // 必须立刻掉回 OPEN 状态
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        assertFalse(breaker.tryAcquirePermission(), "重新跌回 OPEN，不再放行");
    }
}
