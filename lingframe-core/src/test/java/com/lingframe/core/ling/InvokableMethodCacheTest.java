package com.lingframe.core.ling;

import com.lingframe.api.exception.InvalidArgumentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InvokableMethodCache 测试。
 * 覆盖：CRUD、evictByPrefix、边界校验、并发安全。
 */
@DisplayName("InvokableMethodCache 测试")
class InvokableMethodCacheTest {

    private InvokableMethodCache cache;

    @BeforeEach
    void setUp() {
        cache = new InvokableMethodCache();
    }

    private MethodHandle mockHandle() {
        try {
            // 使用一个真实 Method 对象的 MethodHandle
            Method method = String.class.getMethod("length");
            return MethodHandles.lookup().unreflect(method);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== CRUD ====================

    @Nested
    @DisplayName("CRUD 操作")
    class CrudOperations {

        @Test
        @DisplayName("put 和 get 正常工作")
        void putAndGet() throws Exception {
            MethodHandle mh = mockHandle();
            cache.put("ling-1:Service.method", mh);

            assertSame(mh, cache.get("ling-1:Service.method"));
        }

        @Test
        @DisplayName("get 不存在的 key 返回 null")
        void getUnknownReturnsNull() {
            assertNull(cache.get("unknown"));
        }

        @Test
        @DisplayName("remove 后 get 返回 null")
        void removeThenGet() throws Exception {
            MethodHandle mh = mockHandle();
            cache.put("ling-1:Service.method", mh);
            cache.remove("ling-1:Service.method");

            assertNull(cache.get("ling-1:Service.method"));
        }

        @Test
        @DisplayName("clear 清空所有缓存")
        void clearAll() throws Exception {
            MethodHandle mh = mockHandle();
            cache.put("ling-1:Service.method1", mh);
            cache.put("ling-1:Service.method2", mh);

            cache.clear();
            assertEquals(0, cache.size());
            assertNull(cache.get("ling-1:Service.method1"));
        }

        @Test
        @DisplayName("size 返回缓存数量")
        void sizeReturnsCount() throws Exception {
            MethodHandle mh = mockHandle();
            assertEquals(0, cache.size());

            cache.put("ling-1:Service.method1", mh);
            assertEquals(1, cache.size());

            cache.put("ling-1:Service.method2", mh);
            assertEquals(2, cache.size());
        }
    }

    // ==================== computeIfAbsent ====================

    @Nested
    @DisplayName("computeIfAbsent")
    class ComputeIfAbsent {

        @Test
        @DisplayName("key 不存在时调用 mappingFunction")
        void computeIfAbsentCreates() throws Exception {
            MethodHandle mh = mockHandle();
            MethodHandle result = cache.computeIfAbsent("ling-1:Service.method", k -> mh);

            assertSame(mh, result);
            assertSame(mh, cache.get("ling-1:Service.method"));
        }

        @Test
        @DisplayName("key 已存在时不调用 mappingFunction")
        void computeIfAbsentExisting() throws Exception {
            MethodHandle mh1 = mockHandle();
            MethodHandle mh2 = mockHandle();
            cache.put("ling-1:Service.method", mh1);

            MethodHandle result = cache.computeIfAbsent("ling-1:Service.method", k -> mh2);
            assertSame(mh1, result);
        }
    }

    // ==================== evictByPrefix ====================

    @Nested
    @DisplayName("evictByPrefix")
    class EvictByPrefix {

        @Test
        @DisplayName("按前缀驱逐匹配的缓存")
        void evictByPrefixRemovesMatching() throws Exception {
            MethodHandle mh = mockHandle();
            cache.put("ling-1:ServiceA.method", mh);
            cache.put("ling-1:ServiceB.method", mh);
            cache.put("ling-2:ServiceC.method", mh);

            int evicted = cache.evictByPrefix("ling-1:");
            assertEquals(2, evicted);
            assertNull(cache.get("ling-1:ServiceA.method"));
            assertNull(cache.get("ling-1:ServiceB.method"));
            assertNotNull(cache.get("ling-2:ServiceC.method"));
        }

        @Test
        @DisplayName("无匹配前缀时返回 0")
        void evictByPrefixNoMatch() throws Exception {
            MethodHandle mh = mockHandle();
            cache.put("ling-1:Service.method", mh);

            int evicted = cache.evictByPrefix("ling-99:");
            assertEquals(0, evicted);
            assertEquals(1, cache.size());
        }

        @Test
        @DisplayName("null 前缀返回 0")
        void evictByNullPrefix() {
            assertEquals(0, cache.evictByPrefix(null));
        }

        @Test
        @DisplayName("空前缀返回 0")
        void evictByEmptyPrefix() {
            assertEquals(0, cache.evictByPrefix(""));
        }
    }

    // ==================== 边界校验 ====================

    @Nested
    @DisplayName("边界校验")
    class Validation {

        @Test
        @DisplayName("put null fqsid 抛出 InvalidArgumentException")
        void putNullFqsidThrows() throws Exception {
            assertThrows(InvalidArgumentException.class,
                    () -> cache.put(null, mockHandle()));
        }

        @Test
        @DisplayName("put null MethodHandle 抛出 InvalidArgumentException")
        void putNullHandleThrows() {
            assertThrows(InvalidArgumentException.class,
                    () -> cache.put("ling-1:Service.method", null));
        }

        @Test
        @DisplayName("computeIfAbsent null fqsid 抛出 InvalidArgumentException")
        void computeIfAbsentNullFqsidThrows() {
            assertThrows(InvalidArgumentException.class,
                    () -> cache.computeIfAbsent(null, k -> mockHandle()));
        }

        @Test
        @DisplayName("computeIfAbsent null mappingFunction 抛出 InvalidArgumentException")
        void computeIfAbsentNullFunctionThrows() {
            assertThrows(InvalidArgumentException.class,
                    () -> cache.computeIfAbsent("ling-1:Service.method", null));
        }
    }

    // ==================== 并发安全 ====================

    @Nested
    @DisplayName("并发安全")
    class Concurrency {

        @Test
        @DisplayName("并发 put/get 不丢失数据")
        void concurrentPutGet() throws Exception {
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
                        MethodHandle mh = mockHandle();
                        for (int i = 0; i < opsPerThread; i++) {
                            String key = "ling-" + threadId + ":Service.m" + i;
                            cache.put(key, mh);
                            assertNotNull(cache.get(key));
                        }
                    } catch (Exception e) {
                        fail("Unexpected exception: " + e.getMessage());
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            assertEquals(threadCount * opsPerThread, cache.size());
        }
    }
}
