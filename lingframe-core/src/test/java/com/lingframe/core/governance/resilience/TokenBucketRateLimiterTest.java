package com.lingframe.core.governance.resilience;

import com.lingframe.core.resilience.TokenBucketRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TokenBucketRateLimiter 测试")
class TokenBucketRateLimiterTest {

    @Nested
    @DisplayName("基础限流行为")
    class BasicRateLimitingTests {

        @Test
        @DisplayName("容量范围内应放行，超出容量应拒绝")
        void testBasicRateLimiting() {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("test_resource", 5.0, 5.0);

            for (int i = 0; i < 5; i++) {
                assertTrue(limiter.tryAcquire(), "前 5 次请求应被允许");
            }

            assertFalse(limiter.tryAcquire(), "第 6 次请求应因容量耗尽被拒绝");
        }

        @Test
        @DisplayName("等待令牌补充后应能够再次获取")
        void testTokenRefill() throws InterruptedException {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("test_resource", 10.0, 2.0);

            assertTrue(limiter.tryAcquire());
            assertTrue(limiter.tryAcquire());
            assertFalse(limiter.tryAcquire(), "瞬时第 3 次请求应被拒绝");

            Thread.sleep(150);

            assertTrue(limiter.tryAcquire(), "等待后应补充出新的令牌");
        }
    }

    @Nested
    @DisplayName("并发场景")
    class ConcurrencyTests {

        @Test
        @DisplayName("高并发下不应出现超卖")
        void testConcurrentRateLimiting() throws InterruptedException {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("test_resource", 0.0001, 10.0);

            int threadCount = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger rejectCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        latch.await();
                        if (limiter.tryAcquire()) {
                            successCount.incrementAndGet();
                        } else {
                            rejectCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            latch.countDown();
            doneLatch.await(5, TimeUnit.SECONDS);

            assertEquals(10, successCount.get(), "成功获取令牌的次数应等于容量");
            assertEquals(10, rejectCount.get(), "其余请求应被拒绝");

            executor.shutdown();
        }
    }
}
