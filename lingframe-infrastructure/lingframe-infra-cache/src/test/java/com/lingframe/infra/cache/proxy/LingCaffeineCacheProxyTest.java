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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LingCaffeineCacheProxy} 测试。
 * <p>
 * 兼容 Caffeine 2.x（JDK 8 / SB2）与 3.x（JDK 17 / SB3）：
 * <ul>
 *   <li>2.x：{@code getIfPresent(Object key)}</li>
 *   <li>3.x：{@code getIfPresent(K key)}</li>
 * </ul>
 * 因此 mock 目标统一使用 raw {@link Cache}（与生产代理内部委托一致），
 * 避免把 mock 写成 {@code Cache<String,String>} 后，在 3.x 上对
 * {@code ArgumentCaptor<Object>} / {@code any()} 产生 IDE 与编译类型冲突。
 */
@DisplayName("LingCaffeineCacheProxy 测试")
class LingCaffeineCacheProxyTest {

    /**
     * 创建 raw Cache mock。
     * <p>
     * 生产代码内部也以 raw Cache 委托，测试侧保持一致，
     * 才能同时兼容 Caffeine 2.x / 3.x 的泛型签名差异。
     */
    @SuppressWarnings("rawtypes")
    private Cache mockRawCache() {
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
        @SuppressWarnings({"rawtypes", "unchecked"})
        void shouldUseReadAccessForGetIfPresent() {
            Cache target = mockRawCache();
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.READ)).thenReturn(true);

            LingCallContext.setLingId("ling-a");
            Object expectedKey = CacheNamespaceSupport.namespaceKey("users", "user:1");
            when(target.getIfPresent(eq(expectedKey))).thenReturn("tom");

            LingCaffeineCacheProxy<String, String> proxy =
                    new LingCaffeineCacheProxy<>(target, "users", permissionService);

            assertEquals("tom", proxy.getIfPresent("user:1"));
            verify(permissionService).isAllowed("ling-a", "cache:local", AccessType.READ);
            verify(permissionService).audit("ling-a", "cache:local", "getIfPresent", true);
            // 直接 eq 校验 namespaced key，避免 ArgumentCaptor 在 Caffeine 3.x 泛型签名下的类型冲突
            verify(target).getIfPresent(eq(expectedKey));
            assertTrue(CacheNamespaceSupport.isNamespacedKey(expectedKey));
            assertEquals("ling-a", CacheNamespaceSupport.extractLingId(expectedKey));
            assertEquals("users", CacheNamespaceSupport.extractCacheName(expectedKey));
            assertEquals("user:1", CacheNamespaceSupport.denamespaceKey(expectedKey));
        }

        @Test
        @DisplayName("带加载函数的 get 应按 WRITE 鉴权")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void shouldUseWriteAccessForLoadingGet() {
            Cache target = mockRawCache();
            PermissionService permissionService = mock(PermissionService.class);
            Function<String, String> loader = key -> "tom";
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.WRITE)).thenReturn(true);
            when(target.get(any(), any())).thenReturn("tom");

            LingCallContext.setLingId("ling-a");
            LingCaffeineCacheProxy<String, String> proxy =
                    new LingCaffeineCacheProxy<>(target, "users", permissionService);

            assertEquals("tom", proxy.get("user:1", loader));
            verify(permissionService).isAllowed("ling-a", "cache:local", AccessType.WRITE);
            verify(permissionService).audit("ling-a", "cache:local", "get", true);
        }

        @Test
        @DisplayName("stats 应按 READ 鉴权")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void shouldUseReadAccessForStats() {
            Cache target = mockRawCache();
            PermissionService permissionService = mock(PermissionService.class);
            CacheStats stats = CacheStats.empty();
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.READ)).thenReturn(true);
            when(target.stats()).thenReturn(stats);

            LingCallContext.setLingId("ling-a");
            LingCaffeineCacheProxy<String, String> proxy =
                    new LingCaffeineCacheProxy<>(target, "users", permissionService);

            assertSame(stats, proxy.stats());
            verify(permissionService).isAllowed("ling-a", "cache:local", AccessType.READ);
            verify(permissionService).audit("ling-a", "cache:local", "stats", true);
        }

        @Test
        @DisplayName("put 被拒绝时应抛出权限异常")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void shouldRejectPutWhenWritePermissionDenied() {
            Cache target = mockRawCache();
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.WRITE)).thenReturn(false);

            LingCallContext.setLingId("ling-a");
            LingCaffeineCacheProxy<String, String> proxy =
                    new LingCaffeineCacheProxy<>(target, "users", permissionService);

            PermissionDeniedException ex = assertThrows(PermissionDeniedException.class,
                    () -> proxy.put("user:1", "tom"));
            assertEquals("Ling [ling-a] denied access to local cache operation: put", ex.getMessage());
            verify(permissionService).audit("ling-a", "cache:local", "put", false);
            verify(target, never()).put(any(), any());
        }
    }

    @Nested
    @DisplayName("无上下文路径")
    class NoContextTests {

        @Test
        @DisplayName("无上下文且灵核治理关闭时应直接透传到底层缓存")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void shouldBypassPermissionCheckWhenNoContext() {
            Cache target = mockRawCache();
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isLingCoreGovernanceEnabled()).thenReturn(false);
            // 无上下文时 namespaceKey 原样返回 raw key
            when(target.getIfPresent(eq("user:1"))).thenReturn("tom");

            LingCaffeineCacheProxy<String, String> proxy =
                    new LingCaffeineCacheProxy<>(target, permissionService);

            assertEquals("tom", proxy.getIfPresent("user:1"));
            verify(permissionService).isLingCoreGovernanceEnabled();
        }

        @Test
        @DisplayName("无上下文且灵核治理开启时应拒绝访问（fail-closed）")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void shouldRejectWhenNoContextAndLingCoreGovernanceEnabled() {
            Cache target = mockRawCache();
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isLingCoreGovernanceEnabled()).thenReturn(true);

            LingCaffeineCacheProxy<String, String> proxy =
                    new LingCaffeineCacheProxy<>(target, permissionService);

            assertThrows(PermissionDeniedException.class, () -> proxy.getIfPresent("user:1"));
            verify(target, never()).getIfPresent(any());
        }
    }

    @Nested
    @DisplayName("命名空间隔离")
    class NamespaceIsolationTests {

        @Test
        @DisplayName("getAllPresent 返回结果时应还原原始 key")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void shouldDenamespaceReturnedKeysForGetAllPresent() {
            Cache target = mockRawCache();
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.READ)).thenReturn(true);

            // 通过公开 API 构造 namespaced key，避免直接依赖包级私有类型
            LingCallContext.setLingId("ling-a");
            Map namespacedResult = new LinkedHashMap();
            namespacedResult.put(CacheNamespaceSupport.namespaceKey("users", "user:1"), "tom");
            when(target.getAllPresent(any())).thenReturn(namespacedResult);

            LingCaffeineCacheProxy<String, String> proxy =
                    new LingCaffeineCacheProxy<>(target, "users", permissionService);

            Map<String, String> result = proxy.getAllPresent(Collections.singletonList("user:1"));

            assertEquals(1, result.size());
            assertEquals("tom", result.get("user:1"));
        }

        @Test
        @DisplayName("灵元 invalidateAll() 应仅清理本灵元的 namespaced key，不影响其他灵元")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void shouldOnlyInvalidateOwnLingKeysOnInvalidateAll() {
            Cache target = mockRawCache();
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.WRITE)).thenReturn(true);

            // 模拟底层缓存中混合了 ling-a 和 ling-b 的 key（公开 API 构造）
            ConcurrentHashMap<Object, Object> backingMap = new ConcurrentHashMap<>();
            LingCallContext.setLingId("ling-a");
            backingMap.put(CacheNamespaceSupport.namespaceKey("users", "k1"), "v1");
            backingMap.put(CacheNamespaceSupport.namespaceKey("users", "k2"), "v2");
            LingCallContext.setLingId("ling-b");
            backingMap.put(CacheNamespaceSupport.namespaceKey("users", "k3"), "v3");
            backingMap.put(CacheNamespaceSupport.namespaceKey("users", "k4"), "v4");
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
        @SuppressWarnings({"rawtypes", "unchecked"})
        void shouldInvalidateAllWhenNoContext() {
            Cache target = mockRawCache();
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
        @SuppressWarnings({"rawtypes", "unchecked"})
        void shouldRejectInvalidateAllWhenWritePermissionDenied() {
            Cache target = mockRawCache();
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
            // raw 构建避免 2.x/3.x 泛型 builder 差异
            @SuppressWarnings({"rawtypes", "unchecked"})
            Cache target = Caffeine.newBuilder().build();
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.WRITE)).thenReturn(true);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.READ)).thenReturn(true);

            LingCallContext.setLingId("ling-a");
            @SuppressWarnings("unchecked")
            LingCaffeineCacheProxy<String, String> proxy =
                    new LingCaffeineCacheProxy<>(target, "users", permissionService);

            Map<String, String> result = proxy.getAll(
                    Collections.singletonList("user:1"),
                    keys -> Collections.singletonMap("user:1", "tom"));

            // 返回给调用方的 Map 应该用原始 key
            assertEquals(1, result.size());
            assertEquals("tom", result.get("user:1"));
            // 通过代理再读一次，确认 getAll 回填后可被 getIfPresent 命中
            assertEquals("tom", proxy.getIfPresent("user:1"));
            // 直接检查底层 asMap：key 必须是命名空间对象且可反解出原始 key
            @SuppressWarnings("unchecked")
            boolean found = target.asMap().keySet().stream().anyMatch(key ->
                    CacheNamespaceSupport.isNamespacedKey(key)
                            && "ling-a".equals(CacheNamespaceSupport.extractLingId(key))
                            && "users".equals(CacheNamespaceSupport.extractCacheName(key))
                            && "user:1".equals(CacheNamespaceSupport.denamespaceKey(key))
                            && "tom".equals(target.asMap().get(key)));
            assertTrue(found, "底层缓存应存在 namespaced key=ling-a/users/user:1 的条目");
        }
    }

    @Nested
    @DisplayName("其他缓存方法测试")
    class OtherCacheMethodsTests {

        @Test
        @DisplayName("测试 getAll, putAll, invalidate, estimatedSize, cleanUp 等方法的常规操作")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void testVariousCacheOperations() {
            Cache target = mockRawCache();
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.WRITE)).thenReturn(true);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.READ)).thenReturn(true);

            LingCallContext.setLingId("ling-a");
            LingCaffeineCacheProxy<String, String> proxy =
                    new LingCaffeineCacheProxy<>(target, "users", permissionService);

            // 1. getAll —— 用公开 API 构造 namespaced key
            Map loaded = new LinkedHashMap();
            loaded.put(CacheNamespaceSupport.namespaceKey("users", "k1"), "v1");
            doReturn(loaded).when(target).getAll(any(), any());
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
            when(target.asMap()).thenReturn(new ConcurrentHashMap());
            proxy.invalidateAll();
            verify(target).asMap();

            // 6. estimatedSize
            when(target.estimatedSize()).thenReturn(100L);
            assertEquals(100L, proxy.estimatedSize());

            // 7. cleanUp
            proxy.cleanUp();
            verify(target).cleanUp();

            // 8. asMap —— 拒绝暴露原生视图
            assertThrows(UnsupportedOperationException.class, proxy::asMap);

            // 9. policy —— 拒绝暴露策略句柄
            assertThrows(UnsupportedOperationException.class, proxy::policy);
        }
    }
}
