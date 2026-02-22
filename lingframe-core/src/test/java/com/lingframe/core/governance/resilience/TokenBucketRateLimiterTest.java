package com.lingframe.core.governance.resilience;

import com.lingframe.core.resilience.TokenBucketRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    @DisplayName("基础限流测试：在容量内应允许通过，超出容量应被拒绝")
    void testBasicRateLimiting() {
        // 容量为 5，每秒产生 5 个 token
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("test_resource", 5.0, 5.0);

        // 瞬间消耗完 5 个 token
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(), "前 5 次请求应被允许");
        }

        // 第 6 次应被拒绝
        assertFalse(limiter.tryAcquire(), "第 6 次超出容量的请求应被拒绝");
    }

    @Test
    @DisplayName("Token 恢复测试：等待后应该能够再次获取")
    void testTokenRefill() throws InterruptedException {
        // 容量为 2，每秒产生 10 个 token (即每 100 毫秒产生 1 个)
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter("test_resource", 10.0, 2.0);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire(), "第3次瞬时请求应被拒绝");

        // 等待可以恢复至少 1 个 token 的时间(>100ms)
        Thread.sleep(150);

        assertTrue(limiter.tryAcquire(), "等待后令牌桶应已恢复 token");
    }

    @Test
    @DisplayName("高并发限流测试：确保不会超卖")
    void testConcurrentRateLimiting() throws InterruptedException {
        // 容量为 10，恢复极慢（接近不恢复），确保并发下只获取 10 个
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
                    latch.await(); // 枪响齐跑
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

        latch.countDown(); // 起跑
        doneLatch.await(5, TimeUnit.SECONDS);

        assertEquals(10, successCount.get(), "并发量大时，获得令牌成功的次数应恰好为桶容量");
        assertEquals(10, rejectCount.get(), "剩余的请求应当被拒绝");

        executor.shutdown();
    }
}
