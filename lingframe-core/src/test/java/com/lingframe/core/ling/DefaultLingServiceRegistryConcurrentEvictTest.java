package com.lingframe.core.ling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DefaultLingServiceRegistry#evict(String)} 与并发注册的弱一致回归测试。
 * <p>
 * 验证两个不变量：
 * <ul>
 *   <li>evict 与并发 registerServiceMetadata 交错时不抛 ConcurrentModificationException</li>
 *   <li>竞态收敛后（所有线程 join），反向索引最终一致——刚注册的灵元能被反查命中</li>
 * </ul>
 * 注意：ConcurrentHashMap 弱一致在竞态期允许瞬时丢键，本测试只断言「收敛后」终态。
 */
@DisplayName("DefaultLingServiceRegistry 并发 evict 一致性测试")
class DefaultLingServiceRegistryConcurrentEvictTest {

    @Nested
    @DisplayName("evict 与并发注册交错")
    class ConcurrentEvictAndRegister {

        @Test
        @DisplayName("高并发 evict + register 不应抛 CME，且收敛后反向索引一致")
        void shouldStayConsistentUnderConcurrentEvictAndRegister() throws Exception {
            DefaultLingServiceRegistry registry = new DefaultLingServiceRegistry();

            // 预置两个灵元的契约
            registry.registerServiceMetadata(
                    "ling-a:com.example.UserService", "query", new String[]{}, "java.lang.String");
            registry.registerServiceMetadata(
                    "ling-b:com.example.UserService", "query", new String[]{}, "java.lang.String");

            int threads = 8;
            int opsPerThread = 200;
            AtomicInteger errors = new AtomicInteger();

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (int i = 0; i < threads; i++) {
                final int tid = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < opsPerThread; j++) {
                            // 一半线程注册 ling-a/ling-b 契约，另一半交错 evict ling-a
                            if ((tid + j) % 2 == 0) {
                                registry.registerServiceMetadata(
                                        "ling-a:com.example.UserService", "query", new String[]{}, "java.lang.String");
                            } else {
                                registry.evict("ling-a");
                            }
                        }
                    } catch (Throwable t) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发线程未在时限内完成");
            pool.shutdown();

            assertEquals(0, errors.get(), "并发交错期间抛了异常（疑似 CME）");

            // 收敛后：补一次注册，断言反向索引能命中——验「evict 后注册」终态一致
            registry.registerServiceMetadata(
                    "ling-a:com.example.UserService", "query", new String[]{}, "java.lang.String");
            List<String> lingIds = registry.getLingIdsByContractId("com.example.UserService");
            assertNotNull(lingIds);
            assertTrue(lingIds.contains("ling-a"),
                    "evict 收敛后重新注册的灵元应被反向索引命中，实际=" + lingIds);
        }

        @Test
        @DisplayName("evict 后空契约集合应被清空，防内存泄漏")
        void shouldCleanEmptyContractSetsAfterEvict() {
            DefaultLingServiceRegistry registry = new DefaultLingServiceRegistry();

            registry.registerServiceMetadata(
                    "ling-a:com.example.UserService", "query", new String[]{}, "java.lang.String");
            assertFalse(registry.getLingIdsByContractId("com.example.UserService").isEmpty());

            registry.evict("ling-a");

            // evict 后反查应空——空集合已被 removeIf(Set::isEmpty) 清掉
            assertTrue(registry.getLingIdsByContractId("com.example.UserService").isEmpty());
        }
    }
}
