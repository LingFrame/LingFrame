package com.lingframe.infra.cache.proxy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
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
            when(target.getIfPresent(any())).thenReturn("tom");

            LingCallContext.setLingId("ling-a");
            LingCaffeineCacheProxy<String, String> proxy = new LingCaffeineCacheProxy<>(target, "users", permissionService);

            assertEquals("tom", proxy.getIfPresent("user:1"));
            verify(permissionService).isAllowed("ling-a", "cache:local", AccessType.READ);
            verify(permissionService).audit("ling-a", "cache:local", "getIfPresent", true);
            ArgumentCaptor<Object> keyCaptor = ArgumentCaptor.forClass(Object.class);
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
            when(target.get(any(), any())).thenReturn("tom");

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
        @DisplayName("无上下文且灵核治理关闭时应直接透传到底层缓存")
        void shouldBypassPermissionCheckWhenNoContext() {
            Cache<String, String> target = mockStringCache();
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isLingCoreGovernanceEnabled()).thenReturn(false);
            when(target.getIfPresent("user:1")).thenReturn("tom");

            LingCaffeineCacheProxy<String, String> proxy = new LingCaffeineCacheProxy<>(target, permissionService);

            assertEquals("tom", proxy.getIfPresent("user:1"));
            verify(permissionService).isLingCoreGovernanceEnabled();
        }

        @Test
        @DisplayName("无上下文且灵核治理开启时应拒绝访问（fail-closed）")
        void shouldRejectWhenNoContextAndLingCoreGovernanceEnabled() {
            Cache<String, String> target = mockStringCache();
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isLingCoreGovernanceEnabled()).thenReturn(true);

            LingCaffeineCacheProxy<String, String> proxy = new LingCaffeineCacheProxy<>(target, permissionService);

            assertThrows(PermissionDeniedException.class, () -> proxy.getIfPresent("user:1"));
            verify(target, never()).getIfPresent(any());
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
            when(target.getAllPresent(any())).thenReturn((Map) namespacedResult);

            LingCallContext.setLingId("ling-a");
            LingCaffeineCacheProxy<String, String> proxy = new LingCaffeineCacheProxy<>(target, "users", permissionService);

            Map<String, String> result = proxy.getAllPresent(Collections.singletonList("user:1"));

            assertEquals(1, result.size());
            assertEquals("tom", result.get("user:1"));
        }

        @Test
        @DisplayName("灵元 invalidateAll() 应仅清理本灵元的 namespaced key，不影响其他灵元")
        void shouldOnlyInvalidateOwnLingKeysOnInvalidateAll() {
            Cache<String, String> target = mockStringCache();
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.WRITE)).thenReturn(true);

            // 模拟底层缓存中混合了 ling-a 和 ling-b 的 key
            ConcurrentHashMap<Object, Object> backingMap = new ConcurrentHashMap<>();
            backingMap.put(new CacheNamespaceSupport.NamespacedKey("ling-a", "users", "k1"), "v1");
            backingMap.put(new CacheNamespaceSupport.NamespacedKey("ling-a", "users", "k2"), "v2");
            backingMap.put(new CacheNamespaceSupport.NamespacedKey("ling-b", "users", "k3"), "v3");
            backingMap.put(new CacheNamespaceSupport.NamespacedKey("ling-b", "users", "k4"), "v4");
            // 用 doReturn 绕过泛型检查：target.asMap() 返回 ConcurrentMap<K,V>，但此处需放入 NamespacedKey
            doReturn(backingMap).when(target).asMap();

            LingCallContext.setLingId("ling-a");
            LingCaffeineCacheProxy<String, String> proxy =
                    new LingCaffeineCacheProxy<>(target, "users", permissionService);

            proxy.invalidateAll();

            // ling-a 的 key 应被清理
            assertEquals(0, backingMap.entrySet().stream()
                    .filter(e -> "ling-a".equals(CacheNamespaceSupport.extractLingId(e.getKey())))
                    .count());
            // ling-b 的 key 应保留
            assertEquals(2, backingMap.entrySet().stream()
                    .filter(e -> "ling-b".equals(CacheNamespaceSupport.extractLingId(e.getKey())))
                    .count());
        }

        @Test
        @DisplayName("灵核（无上下文）invalidateAll() 应全清缓存")
        void shouldInvalidateAllWhenNoContext() {
            Cache<String, String> target = mockStringCache();
            PermissionService permissionService = mock(PermissionService.class);

            LingCaffeineCacheProxy<String, String> proxy =
                    new LingCaffeineCacheProxy<>(target, "users", permissionService);

            proxy.invalidateAll();
            // 灵核直接调用 target.invalidateAll()
            verify(target).invalidateAll();
            // 不应走 asMap 路径
            verify(target, never()).asMap();
        }

        @Test
        @DisplayName("灵元未被授予 WRITE 权限时，invalidateAll() 应被拒绝")
        void shouldRejectInvalidateAllWhenWritePermissionDenied() {
            Cache<String, String> target = mockStringCache();
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.WRITE)).thenReturn(false);

            LingCallContext.setLingId("ling-a");
            LingCaffeineCacheProxy<String, String> proxy =
                    new LingCaffeineCacheProxy<>(target, "users", permissionService);

            assertThrows(PermissionDeniedException.class, proxy::invalidateAll);
            verify(target, never()).invalidateAll();
            verify(target, never()).asMap();
        }

        @Test
        @DisplayName("getAll 应将 mappingFunction 返回的原始 key 重新命名空间化，保证 Caffeine 内部命中")
        void shouldNamespaceKeysForGetAllMappingFunction() {
            // 用真实 Caffeine Cache 验证 mappingFunction 回调的 key 命名空间化
            Cache<String, String> target = Caffeine.newBuilder().build();
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.WRITE)).thenReturn(true);

            LingCallContext.setLingId("ling-a");
            LingCaffeineCacheProxy<String, String> proxy =
                    new LingCaffeineCacheProxy<>(target, "users", permissionService);

            Map<String, String> result = proxy.getAll(
                    Collections.singletonList("user:1"),
                    keys -> Collections.singletonMap("user:1", "tom"));

            // 返回给调用方的 Map 应该用原始 key
            assertEquals(1, result.size());
            assertEquals("tom", result.get("user:1"));
            // 底层缓存中应该用 NamespacedKey 存储，证明 mappingFunction 返回的 key 被重新命名空间化
            Object namespacedKey = new CacheNamespaceSupport.NamespacedKey("ling-a", "users", "user:1");
            assertEquals("tom", target.getIfPresent(namespacedKey));
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
            doReturn(loaded).when(target).getAll(
                    any(),
                    any()
            );
            Map<String, String> getAllResult = proxy.getAll(
                    Collections.singletonList("k1"),
                    keys -> Collections.singletonMap("k1", "v1")
            );
            assertEquals("v1", getAllResult.get("k1"));

            // 2. putAll
            proxy.putAll(Collections.singletonMap("k1", "v1"));
            verify(target).putAll(any());

            // 3. invalidate
            proxy.invalidate("k1");
            verify(target).invalidate(any());

            // 4. invalidateAll(Iterable)
            proxy.invalidateAll(Collections.singletonList("k1"));
            verify(target).invalidateAll(any());

            // 5. invalidateAll() —— 灵元场景按 lingId 前缀清理
            when(target.asMap()).thenReturn(new ConcurrentHashMap<>());
            proxy.invalidateAll();
            verify(target).asMap();

            // 6. estimatedSize
            when(target.estimatedSize()).thenReturn(100L);
            assertEquals(100L, proxy.estimatedSize());

            // 7. cleanUp
            proxy.cleanUp();
            verify(target).cleanUp();

            // 8. asMap —— 拒绝暴露原生视图
            assertThrows(UnsupportedOperationException.class, () -> proxy.asMap());

            // 9. policy —— 拒绝暴露策略句柄
            assertThrows(UnsupportedOperationException.class, () -> proxy.policy());
        }
    }
}
