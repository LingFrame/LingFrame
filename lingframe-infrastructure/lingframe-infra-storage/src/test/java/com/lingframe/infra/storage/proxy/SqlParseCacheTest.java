package com.lingframe.infra.storage.proxy;

import com.lingframe.api.security.AccessType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SqlParseCache 测试")
class SqlParseCacheTest {

    @AfterEach
    void tearDown() {
        SqlParseCache.clearAll();
    }

    /** 构造一个最小 plan，仅含 accessType，不依赖 SQL 解析 */
    private static SqlPermissionSupport.SqlPermissionPlan plan(AccessType accessType) {
        return new SqlPermissionSupport.SqlPermissionPlan(accessType, Collections.emptyList());
    }

    @Nested
    @DisplayName("按灵元隔离")
    class LingIsolationTests {

        @Test
        @DisplayName("不同灵元之间应隔离 SQL 解析缓存")
        void shouldIsolateByLingId() {
            SqlParseCache.put("ling-a", "select 1", plan(AccessType.READ));

            assertNull(SqlParseCache.get("ling-b", "select 1"));
            assertEquals(AccessType.READ, SqlParseCache.get("ling-a", "select 1").getAccessType());
        }

        @Test
        @DisplayName("null/空 lingId 应归一化为灵核标识（lingcore-app）")
        void shouldNormalizeNullLingId() {
            SqlParseCache.put(null, "select 1", plan(AccessType.READ));
            assertEquals(AccessType.READ, SqlParseCache.get(null, "select 1").getAccessType());
            assertEquals(AccessType.READ, SqlParseCache.get("", "select 1").getAccessType());
        }
    }

    @Nested
    @DisplayName("缓存清理")
    class CacheEvictionTests {

        @Test
        @DisplayName("按灵元清理后应无法再读取缓存")
        void shouldEvictLingCache() {
            SqlParseCache.put("ling-c", "select 1", plan(AccessType.READ));
            SqlParseCache.evictLing("ling-c");
            assertNull(SqlParseCache.get("ling-c", "select 1"));
        }

        @Test
        @DisplayName("clearAll 后所有灵元的缓存应被清空")
        void shouldClearAll() {
            SqlParseCache.put("ling-a", "select 1", plan(AccessType.READ));
            SqlParseCache.put("ling-b", "select 2", plan(AccessType.WRITE));
            SqlParseCache.clearAll();
            assertNull(SqlParseCache.get("ling-a", "select 1"));
            assertNull(SqlParseCache.get("ling-b", "select 2"));
        }

        @Test
        @DisplayName("evictLing 后同灵元再次 put 应正常工作")
        void shouldAllowPutAgainAfterEvict() {
            SqlParseCache.put("ling-d", "select 1", plan(AccessType.READ));
            SqlParseCache.evictLing("ling-d");
            SqlParseCache.put("ling-d", "select 1", plan(AccessType.WRITE));
            assertEquals(AccessType.WRITE, SqlParseCache.get("ling-d", "select 1").getAccessType());
        }
    }

    @Nested
    @DisplayName("LRU 淘汰与并发安全")
    class LruAndConcurrencyTests {

        @Test
        @DisplayName("超过 maximumSize 后应触发 LRU 淘汰，缓存大小受限")
        void shouldEvictByLruWhenExceedingMaxSize() {
            // 持续 put 不同 key，验证缓存大小不会无限增长
            String lingId = "ling-lru";
            SqlPermissionSupport.SqlPermissionPlan p = plan(AccessType.READ);
            for (int i = 0; i < 6000; i++) {
                SqlParseCache.put(lingId, "select " + i, p);
            }
            int size = SqlParseCache.size(lingId);
            // Caffeine maximumSize=5000，6000 个 entry 后大小不应超过 5000
            assertTrue(size <= 5000, "cache size should be bounded by maximumSize, but was " + size);
        }

        @Test
        @DisplayName("并发 put 同一灵元缓存应无异常且大小受限")
        void shouldBeThreadSafeUnderConcurrentPuts() throws InterruptedException {
            String lingId = "ling-concurrent";
            int threadCount = 16;
            int putsPerThread = 200;
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errors = new AtomicInteger();
            SqlPermissionSupport.SqlPermissionPlan p = plan(AccessType.READ);
            for (int t = 0; t < threadCount; t++) {
                final int base = t * putsPerThread;
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < putsPerThread; i++) {
                            SqlParseCache.put(lingId, "select " + (base + i), p);
                        }
                    } catch (Throwable e) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertTrue(latch.await(30, TimeUnit.SECONDS));
            pool.shutdown();
            assertEquals(0, errors.get(), "concurrent put should not throw");
            int size = SqlParseCache.size(lingId);
            assertTrue(size <= 5000, "cache size should be bounded under concurrency, but was " + size);
        }
    }
}
