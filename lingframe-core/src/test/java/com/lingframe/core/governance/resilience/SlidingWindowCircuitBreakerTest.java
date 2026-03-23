package com.lingframe.core.governance.resilience;

import com.lingframe.core.resilience.CircuitBreaker;
import com.lingframe.core.resilience.SlidingWindowCircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SlidingWindowCircuitBreaker 测试")
class SlidingWindowCircuitBreakerTest {

    @Nested
    @DisplayName("关闭态与打开态")
    class ClosedAndOpenStateTests {

        @Test
        @DisplayName("正常调用应保持 CLOSED 状态")
        void testNormalExecution() {
            SlidingWindowCircuitBreaker breaker = new SlidingWindowCircuitBreaker("res", 50, 50, 100, 5, 3, 1000);

            assertTrue(breaker.tryAcquirePermission());
            breaker.onSuccess(10, TimeUnit.MILLISECONDS);

            assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        }

        @Test
        @DisplayName("错误率达到阈值时应进入 OPEN 状态")
        void testTripToOpen() {
            SlidingWindowCircuitBreaker breaker = new SlidingWindowCircuitBreaker("res", 50, 50, 100, 5, 3, 1000);

            breaker.onSuccess(10, TimeUnit.MILLISECONDS);
            breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException("fail"));
            breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException("fail"));

            assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
            assertFalse(breaker.tryAcquirePermission(), "处于 OPEN 状态时不应放行请求");
        }

        @Test
        @DisplayName("未达到最小请求数时不应提前熔断")
        void testNotTripBelowMinRequests() {
            SlidingWindowCircuitBreaker breaker = new SlidingWindowCircuitBreaker("res", 50, 50, 100, 10, 5, 1000);

            breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException("fail"));
            breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException("fail"));

            assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
            assertTrue(breaker.tryAcquirePermission());
        }
    }

    @Nested
    @DisplayName("慢调用与半开恢复")
    class SlowCallAndHalfOpenTests {

        @Test
        @DisplayName("慢调用比例达到阈值时应进入 OPEN 状态")
        void testSlowCallTripToOpen() {
            SlidingWindowCircuitBreaker breaker = new SlidingWindowCircuitBreaker("res", 50, 50, 100, 5, 3, 1000);

            breaker.onSuccess(10, TimeUnit.MILLISECONDS);
            breaker.onSuccess(150, TimeUnit.MILLISECONDS);
            breaker.onSuccess(200, TimeUnit.MILLISECONDS);

            assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
            assertFalse(breaker.tryAcquirePermission());
        }

        @Test
        @DisplayName("半开探测连续成功后应恢复到 CLOSED 状态")
        void testHalfOpenRecovery() throws InterruptedException {
            SlidingWindowCircuitBreaker breaker = new SlidingWindowCircuitBreaker("res", 50, 50, 100, 5, 3, 100);

            breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException("fail"));
            breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException("fail"));
            breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException("fail"));

            assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

            Thread.sleep(150);

            assertTrue(breaker.tryAcquirePermission(), "应允许探测请求");
            assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState());

            for (int i = 0; i < 10; i++) {
                breaker.onSuccess(10, TimeUnit.MILLISECONDS);
            }

            assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        }

        @Test
        @DisplayName("半开探测失败后应重新回落到 OPEN 状态")
        void testHalfOpenFailBackToOpen() throws InterruptedException {
            SlidingWindowCircuitBreaker breaker = new SlidingWindowCircuitBreaker("res", 50, 50, 100, 5, 3, 100);

            breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException());
            breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException());
            breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException());
            assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

            Thread.sleep(150);

            assertTrue(breaker.tryAcquirePermission());
            assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState());

            breaker.onError(10, TimeUnit.MILLISECONDS, new RuntimeException());

            assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
            assertFalse(breaker.tryAcquirePermission(), "重新回到 OPEN 后不应继续放行");
        }
    }
}
