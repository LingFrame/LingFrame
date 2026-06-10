package com.lingframe.core.ling;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.InstanceStateChangedEvent;
import com.lingframe.core.fsm.InstanceStatus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多灵元并发卸载集成测试。
 * <p>
 * 验证多个灵元同时卸载时：
 * <ul>
 *   <li>RuntimeCoordinator 状态一致</li>
 *   <li>EventBus 不死锁</li>
 *   <li>同一灵元并发卸载幂等安全</li>
 * </ul>
 */
@DisplayName("多灵元并发卸载集成测试")
class ConcurrentLingUnloadIntegrationTest {

    private static final int LING_COUNT = 5;

    private EventBus eventBus;
    private RuntimeCoordinator runtimeCoordinator;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        runtimeCoordinator = new RuntimeCoordinator(eventBus);
        runtimeCoordinator.start();
    }

    @AfterEach
    void tearDown() {
        if (runtimeCoordinator != null) {
            runtimeCoordinator.stop();
        }
        if (eventBus != null) {
            eventBus.shutdown();
        }
    }

    @Test
    @DisplayName("多个灵元并发卸载不抛异常且状态一致")
    void concurrentUnloadNoExceptionAndConsistentState() throws Exception {
        // 注册灵元到 RuntimeCoordinator
        for (int i = 0; i < LING_COUNT; i++) {
            runtimeCoordinator.register("ling-" + i);
        }

        // 并发卸载
        ExecutorService executor = Executors.newFixedThreadPool(LING_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(LING_COUNT);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < LING_COUNT; i++) {
            final String lingId = "ling-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    runtimeCoordinator.shutdown(lingId);
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 同时触发
        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "所有卸载应在 10s 内完成");
        assertEquals(0, errorCount.get(), "不应有卸载异常");

        // 验证所有灵元都进入 STOPPING 或 REMOVED
        for (int i = 0; i < LING_COUNT; i++) {
            RuntimeStatus status = runtimeCoordinator.getStatus("ling-" + i);
            assertTrue(status == RuntimeStatus.INACTIVE
                            || status == RuntimeStatus.STOPPING,
                    "ling-" + i + " 状态应为 INACTIVE 或 STOPPING，实际: " + status);
        }

        executor.shutdown();
    }

    @Test
    @DisplayName("并发卸载时 EventBus 不死锁")
    void concurrentUnloadEventBusNoDeadlock() throws Exception {
        // 注册灵元并驱动到 ACTIVE
        for (int i = 0; i < LING_COUNT; i++) {
            runtimeCoordinator.register("ling-" + i);
            eventBus.publish(new InstanceStateChangedEvent("ling-" + i, "v1",
                    InstanceStatus.STARTING, InstanceStatus.READY));
        }

        ExecutorService executor = Executors.newFixedThreadPool(LING_COUNT);
        CountDownLatch doneLatch = new CountDownLatch(LING_COUNT);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < LING_COUNT; i++) {
            final String lingId = "ling-" + i;
            executor.submit(() -> {
                try {
                    runtimeCoordinator.shutdown(lingId);
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 5s 内应完成，否则视为死锁
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "并发卸载不应死锁");
        assertEquals(0, errorCount.get(), "不应有卸载异常");

        executor.shutdown();
    }

    @Test
    @DisplayName("同一灵元并发卸载幂等安全")
    void sameLingConcurrentUnloadIdempotent() throws Exception {
        String lingId = "ling-duplicate";
        runtimeCoordinator.register(lingId);

        // 10 个线程同时卸载同一个灵元
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    runtimeCoordinator.shutdown(lingId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // 预期部分线程可能失败（状态已变更）
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "并发卸载应在 5s 内完成");

        // 至少有一个成功
        assertTrue(successCount.get() >= 1, "至少一个卸载应成功");

        // 最终状态一致
        RuntimeStatus status = runtimeCoordinator.getStatus(lingId);
        assertTrue(status == RuntimeStatus.INACTIVE
                        || status == RuntimeStatus.STOPPING,
                "最终状态应为 INACTIVE 或 STOPPING，实际: " + status);

        executor.shutdown();
    }
}
