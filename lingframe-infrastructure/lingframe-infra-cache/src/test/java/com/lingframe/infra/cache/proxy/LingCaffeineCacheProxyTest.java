package com.lingframe.infra.cache.proxy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("LingCaffeineCacheProxy 测试")
class LingCaffeineCacheProxyTest {

    @SuppressWarnings("unchecked")
    private Cache<String, String> mockStringCache() {
        return mock(Cache.class);
    }

    @AfterEach
    void tearDown() {
        LingCallContext.clear();
    }

    @Nested
    @DisplayName("访问类型映射")
    class AccessTypeMappingTests {

        @Test
        @DisplayName("getIfPresent 应按 READ 鉴权")
        void shouldUseReadAccessForGetIfPresent() {
            Cache<String, String> target = mockStringCache();
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.READ)).thenReturn(true);
            when(target.getIfPresent(org.mockito.ArgumentMatchers.any())).thenReturn("tom");

            LingCallContext.setLingId("ling-a");
            LingCaffeineCacheProxy<String, String> proxy = new LingCaffeineCacheProxy<>(target, "users", permissionService);

            assertEquals("tom", proxy.getIfPresent("user:1"));
            verify(permissionService).isAllowed("ling-a", "cache:local", AccessType.READ);
            verify(permissionService).audit("ling-a", "cache:local", "getIfPresent", true);
            org.mockito.ArgumentCaptor<Object> keyCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(target).getIfPresent(keyCaptor.capture());
            CacheNamespaceSupport.NamespacedKey namespacedKey =
                    assertInstanceOf(CacheNamespaceSupport.NamespacedKey.class, keyCaptor.getValue());
            assertEquals("ling-a", namespacedKey.getLingId());
            assertEquals("users", namespacedKey.getCacheName());
            assertEquals("user:1", namespacedKey.getRawKey());
        }

        @Test
        @DisplayName("带加载函数的 get 应按 WRITE 鉴权")
        void shouldUseWriteAccessForLoadingGet() {
            Cache<String, String> target = mockStringCache();
            PermissionService permissionService = mock(PermissionService.class);
            Function<String, String> loader = key -> "tom";
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.WRITE)).thenReturn(true);
            when(target.get(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn("tom");

            LingCallContext.setLingId("ling-a");
            LingCaffeineCacheProxy<String, String> proxy = new LingCaffeineCacheProxy<>(target, "users", permissionService);

            assertEquals("tom", proxy.get("user:1", loader));
            verify(permissionService).isAllowed("ling-a", "cache:local", AccessType.WRITE);
            verify(permissionService).audit("ling-a", "cache:local", "get", true);
        }

        @Test
        @DisplayName("stats 应按 READ 鉴权")
        void shouldUseReadAccessForStats() {
            Cache<String, String> target = mockStringCache();
            PermissionService permissionService = mock(PermissionService.class);
            CacheStats stats = CacheStats.of(3, 2, 1, 5, 7, 11, 13);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.READ)).thenReturn(true);
            when(target.stats()).thenReturn(stats);

            LingCallContext.setLingId("ling-a");
            LingCaffeineCacheProxy<String, String> proxy = new LingCaffeineCacheProxy<>(target, "users", permissionService);

            assertSame(stats, proxy.stats());
            verify(permissionService).isAllowed("ling-a", "cache:local", AccessType.READ);
            verify(permissionService).audit("ling-a", "cache:local", "stats", true);
        }

        @Test
        @DisplayName("put 被拒绝时应抛出权限异常")
        void shouldRejectPutWhenWritePermissionDenied() {
            Cache<String, String> target = mockStringCache();
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.WRITE)).thenReturn(false);

            LingCallContext.setLingId("ling-a");
            LingCaffeineCacheProxy<String, String> proxy = new LingCaffeineCacheProxy<>(target, "users", permissionService);

            PermissionDeniedException ex = assertThrows(PermissionDeniedException.class, () -> proxy.put("user:1", "tom"));
            assertEquals("Ling [ling-a] denied access to local cache operation: put", ex.getMessage());
            verify(permissionService).audit("ling-a", "cache:local", "put", false);
            verify(target, never()).put("user:1", "tom");
        }
    }

    @Nested
    @DisplayName("无上下文路径")
    class NoContextTests {

        @Test
        @DisplayName("无上下文时应直接透传到底层缓存")
        void shouldBypassPermissionCheckWhenNoContext() {
            Cache<String, String> target = mockStringCache();
            PermissionService permissionService = mock(PermissionService.class);
            when(target.getIfPresent("user:1")).thenReturn("tom");

            LingCaffeineCacheProxy<String, String> proxy = new LingCaffeineCacheProxy<>(target, permissionService);

            assertEquals("tom", proxy.getIfPresent("user:1"));
            verifyNoInteractions(permissionService);
        }
    }

    @Nested
    @DisplayName("命名空间隔离")
    class NamespaceIsolationTests {

        @Test
        @DisplayName("getAllPresent 返回结果时应还原原始 key")
        void shouldDenamespaceReturnedKeysForGetAllPresent() {
            Cache<String, String> target = mockStringCache();
            PermissionService permissionService = mock(PermissionService.class);
            Map<Object, String> namespacedResult = new LinkedHashMap<>();
            namespacedResult.put(new CacheNamespaceSupport.NamespacedKey("ling-a", "users", "user:1"), "tom");
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.READ)).thenReturn(true);
            when(target.getAllPresent(org.mockito.ArgumentMatchers.any())).thenReturn((Map) namespacedResult);

            LingCallContext.setLingId("ling-a");
            LingCaffeineCacheProxy<String, String> proxy = new LingCaffeineCacheProxy<>(target, "users", permissionService);

            Map<String, String> result = proxy.getAllPresent(Collections.singletonList("user:1"));

            assertEquals(1, result.size());
            assertEquals("tom", result.get("user:1"));
        }
    }

    @Nested
    @DisplayName("其他缓存方法测试")
    class OtherCacheMethodsTests {

        @Test
        @DisplayName("测试 getAll, putAll, invalidate, estimatedSize, cleanUp 等方法的常规操作")
        void testVariousCacheOperations() {
            Cache<String, String> target = mockStringCache();
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.WRITE)).thenReturn(true);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.READ)).thenReturn(true);

            LingCallContext.setLingId("ling-a");
            LingCaffeineCacheProxy<String, String> proxy = new LingCaffeineCacheProxy<>(target, "users", permissionService);

            // 1. getAll
            Map<Object, Object> loaded = new LinkedHashMap<>();
            loaded.put(new CacheNamespaceSupport.NamespacedKey("ling-a", "users", "k1"), "v1");
            org.mockito.Mockito.doReturn(loaded).when(target).getAll(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()
            );
            Map<String, String> getAllResult = proxy.getAll(
                    Collections.singletonList("k1"),
                    keys -> Collections.singletonMap("k1", "v1")
            );
            assertEquals("v1", getAllResult.get("k1"));

            // 2. putAll
            proxy.putAll(Collections.singletonMap("k1", "v1"));
            verify(target).putAll(org.mockito.ArgumentMatchers.any());

            // 3. invalidate
            proxy.invalidate("k1");
            verify(target).invalidate(org.mockito.ArgumentMatchers.any());

            // 4. invalidateAll(Iterable)
            proxy.invalidateAll(Collections.singletonList("k1"));
            verify(target).invalidateAll(org.mockito.ArgumentMatchers.any());

            // 5. invalidateAll()
            proxy.invalidateAll();
            verify(target).invalidateAll();

            // 6. estimatedSize
            when(target.estimatedSize()).thenReturn(100L);
            assertEquals(100L, proxy.estimatedSize());

            // 7. cleanUp
            proxy.cleanUp();
            verify(target).cleanUp();

            // 8. asMap
            proxy.asMap();
            verify(target).asMap();

            // 9. policy
            proxy.policy();
            verify(target).policy();
        }
    }
}
