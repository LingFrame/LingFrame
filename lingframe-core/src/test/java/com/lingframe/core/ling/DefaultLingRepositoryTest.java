package com.lingframe.core.ling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DefaultLingRepository 测试。
 * 覆盖：CRUD、并发安全、边界校验。
 */
@DisplayName("DefaultLingRepository 测试")
class DefaultLingRepositoryTest {

    private DefaultLingRepository repository;

    @BeforeEach
    void setUp() {
        repository = new DefaultLingRepository();
    }

    private LingRuntime mockRuntime(String lingId) {
        LingRuntime runtime = mock(LingRuntime.class);
        when(runtime.getLingId()).thenReturn(lingId);
        return runtime;
    }

    // ==================== 注册与查询 ====================

    @Nested
    @DisplayName("注册与查询")
    class RegisterAndQuery {

        @Test
        @DisplayName("注册后可通过 lingId 查询")
        void registerAndGet() {
            LingRuntime runtime = mockRuntime("ling-1");
            repository.register(runtime);

            assertSame(runtime, repository.getRuntime("ling-1"));
            assertTrue(repository.hasRuntime("ling-1"));
        }

        @Test
        @DisplayName("未注册的 lingId 返回 null")
        void getUnknownReturnsNull() {
            assertNull(repository.getRuntime("unknown"));
            assertFalse(repository.hasRuntime("unknown"));
        }

        @Test
        @DisplayName("注册 null runtime 抛出 IllegalArgumentException")
        void registerNullThrows() {
            assertThrows(IllegalArgumentException.class, () -> repository.register(null));
        }

        @Test
        @DisplayName("注册 lingId 为 null 的 runtime 抛出 IllegalArgumentException")
        void registerNullLingIdThrows() {
            LingRuntime runtime = mock(LingRuntime.class);
            when(runtime.getLingId()).thenReturn(null);
            assertThrows(IllegalArgumentException.class, () -> repository.register(runtime));
        }

        @Test
        @DisplayName("重复注册覆盖旧值")
        void registerOverwrites() {
            LingRuntime first = mockRuntime("ling-1");
            LingRuntime second = mockRuntime("ling-1");
            repository.register(first);
            repository.register(second);

            assertSame(second, repository.getRuntime("ling-1"));
        }
    }

    // ==================== 注销 ====================

    @Nested
    @DisplayName("注销")
    class Deregister {

        @Test
        @DisplayName("注销后不可查询")
        void deregisterRemoves() {
            LingRuntime runtime = mockRuntime("ling-1");
            repository.register(runtime);
            repository.deregister("ling-1");

            assertNull(repository.getRuntime("ling-1"));
            assertFalse(repository.hasRuntime("ling-1"));
        }

        @Test
        @DisplayName("注销不存在的 lingId 返回 null")
        void deregisterUnknownReturnsNull() {
            assertNull(repository.deregister("unknown"));
        }

        @Test
        @DisplayName("注销返回被移除的 runtime")
        void deregisterReturnsRemoved() {
            LingRuntime runtime = mockRuntime("ling-1");
            repository.register(runtime);

            LingRuntime removed = repository.deregister("ling-1");
            assertSame(runtime, removed);
        }
    }

    // ==================== getAllRuntimes ====================

    @Nested
    @DisplayName("getAllRuntimes")
    class GetAllRuntimes {

        @Test
        @DisplayName("空仓库返回空集合")
        void emptyRepositoryReturnsEmpty() {
            Collection<LingRuntime> all = repository.getAllRuntimes();
            assertNotNull(all);
            assertTrue(all.isEmpty());
        }

        @Test
        @DisplayName("返回所有已注册的 runtime")
        void returnsAllRuntimes() {
            repository.register(mockRuntime("ling-1"));
            repository.register(mockRuntime("ling-2"));
            repository.register(mockRuntime("ling-3"));

            Collection<LingRuntime> all = repository.getAllRuntimes();
            assertEquals(3, all.size());
        }
    }

    // ==================== 并发安全 ====================

    @Nested
    @DisplayName("并发安全")
    class Concurrency {

        @Test
        @DisplayName("并发注册与查询不丢失数据")
        void concurrentRegisterAndGet() throws Exception {
            int threadCount = 8;
            int opsPerThread = 100;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < opsPerThread; i++) {
                            String lingId = "ling-" + threadId + "-" + i;
                            repository.register(mockRuntime(lingId));
                            assertNotNull(repository.getRuntime(lingId));
                            assertTrue(repository.hasRuntime(lingId));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            assertEquals(threadCount * opsPerThread, repository.getAllRuntimes().size());
        }

        @Test
        @DisplayName("并发读写混合安全")
        void concurrentReadWriteMixed() throws Exception {
            // 预注册
            for (int i = 0; i < 100; i++) {
                repository.register(mockRuntime("ling-" + i));
            }

            int threadCount = 8;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            AtomicInteger errors = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < 100; i++) {
                            if (threadId % 2 == 0) {
                                // 读
                                repository.getRuntime("ling-" + (i % 100));
                                repository.hasRuntime("ling-" + (i % 100));
                            } else {
                                // 写：注销再注册
                                String lingId = "ling-" + (i % 100);
                                repository.deregister(lingId);
                                repository.register(mockRuntime(lingId));
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            assertEquals(0, errors.get());
        }
    }
}
