package com.lingframe.infra.cache.spring;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.infra.cache.proxy.CacheNamespaceSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;

import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("LingSpringCacheProxy 测试")
class LingSpringCacheProxyTest {

    @AfterEach
    void tearDown() {
        LingCallContext.clear();
    }

    @Nested
    @DisplayName("访问类型映射")
    class AccessTypeMappingTests {

        @Test
        @DisplayName("普通 get 应按 READ 鉴权")
        void shouldUseReadAccessForSimpleGet() {
            Cache target = mock(Cache.class);
            Cache.ValueWrapper valueWrapper = mock(Cache.ValueWrapper.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.READ)).thenReturn(true);
            when(target.get(org.mockito.ArgumentMatchers.any())).thenReturn(valueWrapper);

            LingCallContext.setLingId("ling-a");
            when(target.getName()).thenReturn("users");
            LingSpringCacheProxy proxy = new LingSpringCacheProxy(target, permissionService);

            assertSame(valueWrapper, proxy.get("user:1"));
            verify(permissionService).isAllowed("ling-a", "cache:local", AccessType.READ);
            verify(permissionService).audit("ling-a", "cache:local", "get", true);
            org.mockito.ArgumentCaptor<Object> keyCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(target).get(keyCaptor.capture());
            Object capturedKey = keyCaptor.getValue();
            assertTrue(CacheNamespaceSupport.isNamespacedKey(capturedKey));
            assertEquals("ling-a", CacheNamespaceSupport.extractLingId(capturedKey));
            assertEquals("users", CacheNamespaceSupport.extractCacheName(capturedKey));
            assertEquals("user:1", CacheNamespaceSupport.denamespaceKey(capturedKey));
        }

        @Test
        @DisplayName("带 valueLoader 的 get 应按 WRITE 鉴权")
        void shouldUseWriteAccessForLoadingGet() {
            Cache target = mock(Cache.class);
            PermissionService permissionService = mock(PermissionService.class);
            Callable<String> loader = () -> "tom";
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.WRITE)).thenReturn(true);
            when(target.get(org.mockito.ArgumentMatchers.any(), same(loader))).thenReturn("tom");

            LingCallContext.setLingId("ling-a");
            when(target.getName()).thenReturn("users");
            LingSpringCacheProxy proxy = new LingSpringCacheProxy(target, permissionService);

            assertEquals("tom", proxy.get("user:1", loader));
            verify(permissionService).isAllowed("ling-a", "cache:local", AccessType.WRITE);
            verify(permissionService).audit("ling-a", "cache:local", "get", true);
        }

        @Test
        @DisplayName("getNativeCache 应拒绝暴露原生句柄")
        void shouldRejectNativeCacheExposure() {
            Cache target = mock(Cache.class);
            PermissionService permissionService = mock(PermissionService.class);

            LingCallContext.setLingId("ling-a");
            when(target.getName()).thenReturn("users");
            LingSpringCacheProxy proxy = new LingSpringCacheProxy(target, permissionService);

            assertThrows(UnsupportedOperationException.class, () -> proxy.getNativeCache());
        }

        @Test
        @DisplayName("put 被拒绝时应抛出权限异常")
        void shouldRejectPutWhenWritePermissionDenied() {
            Cache target = mock(Cache.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.WRITE)).thenReturn(false);

            LingCallContext.setLingId("ling-a");
            when(target.getName()).thenReturn("users");
            LingSpringCacheProxy proxy = new LingSpringCacheProxy(target, permissionService);

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
            Cache target = mock(Cache.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(target.getName()).thenReturn("users");

            LingSpringCacheProxy proxy = new LingSpringCacheProxy(target, permissionService);

            assertEquals("users", proxy.getName());
            verifyNoInteractions(permissionService);
        }
    }

    @Nested
    @DisplayName("命名空间隔离")
    class NamespaceIsolationTests {

        @Test
        @DisplayName("put 应按灵元与缓存名包装底层 key")
        void shouldNamespaceKeyForPut() {
            Cache target = mock(Cache.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.WRITE)).thenReturn(true);
            when(target.getName()).thenReturn("users");

            LingCallContext.setLingId("ling-a");
            LingSpringCacheProxy proxy = new LingSpringCacheProxy(target, permissionService);

            proxy.put("user:1", "tom");

            org.mockito.ArgumentCaptor<Object> keyCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(target).put(keyCaptor.capture(), eq("tom"));
            Object capturedKey = keyCaptor.getValue();
            assertTrue(CacheNamespaceSupport.isNamespacedKey(capturedKey));
            assertEquals("ling-a", CacheNamespaceSupport.extractLingId(capturedKey));
            assertEquals("users", CacheNamespaceSupport.extractCacheName(capturedKey));
            assertEquals("user:1", CacheNamespaceSupport.denamespaceKey(capturedKey));
        }
    }

    @Nested
    @DisplayName("批量清理治理")
    class BulkClearGovernanceTests {

        @Test
        @DisplayName("灵元调用 clear() 应被拒绝——会清空所有灵元缓存")
        void shouldRejectClearForLing() {
            Cache target = mock(Cache.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.WRITE)).thenReturn(true);
            when(target.getName()).thenReturn("users");

            LingCallContext.setLingId("ling-a");
            LingSpringCacheProxy proxy = new LingSpringCacheProxy(target, permissionService);

            assertThrows(UnsupportedOperationException.class, proxy::clear);
            verify(target, never()).clear();
        }

        @Test
        @DisplayName("灵元调用 invalidate() 应被拒绝——会清空所有灵元缓存")
        void shouldRejectInvalidateForLing() {
            Cache target = mock(Cache.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.WRITE)).thenReturn(true);
            when(target.getName()).thenReturn("users");

            LingCallContext.setLingId("ling-a");
            LingSpringCacheProxy proxy = new LingSpringCacheProxy(target, permissionService);

            assertThrows(UnsupportedOperationException.class, proxy::invalidate);
            verify(target, never()).invalidate();
        }

        @Test
        @DisplayName("灵核（无上下文）调用 clear() 应放行")
        void shouldAllowClearForLingCore() {
            Cache target = mock(Cache.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(target.getName()).thenReturn("users");

            LingSpringCacheProxy proxy = new LingSpringCacheProxy(target, permissionService);

            proxy.clear();
            verify(target).clear();
        }

        @Test
        @DisplayName("灵核（无上下文）调用 invalidate() 应放行")
        void shouldAllowInvalidateForLingCore() {
            Cache target = mock(Cache.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(target.getName()).thenReturn("users");
            when(target.invalidate()).thenReturn(true);

            LingSpringCacheProxy proxy = new LingSpringCacheProxy(target, permissionService);

            assertTrue(proxy.invalidate());
            verify(target).invalidate();
        }

        @Test
        @DisplayName("灵元调用 evict(key) 应放行——精确 key 清理不跨灵元影响")
        void shouldAllowEvictForLing() {
            Cache target = mock(Cache.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.WRITE)).thenReturn(true);
            when(target.getName()).thenReturn("users");

            LingCallContext.setLingId("ling-a");
            LingSpringCacheProxy proxy = new LingSpringCacheProxy(target, permissionService);

            proxy.evict("user:1");
            verify(target).evict(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("灵元调用 evictIfPresent(key) 应放行——精确 key 判断不跨灵元影响")
        void shouldAllowEvictIfPresentForLing() {
            Cache target = mock(Cache.class);
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.WRITE)).thenReturn(true);
            when(target.getName()).thenReturn("users");
            when(target.evictIfPresent(org.mockito.ArgumentMatchers.any())).thenReturn(true);

            LingCallContext.setLingId("ling-a");
            LingSpringCacheProxy proxy = new LingSpringCacheProxy(target, permissionService);

            assertTrue(proxy.evictIfPresent("user:1"));
            verify(target).evictIfPresent(org.mockito.ArgumentMatchers.any());
        }
    }
}
