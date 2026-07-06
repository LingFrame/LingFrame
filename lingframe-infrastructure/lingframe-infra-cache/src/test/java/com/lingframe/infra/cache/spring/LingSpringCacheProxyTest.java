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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
            CacheNamespaceSupport.NamespacedKey namespacedKey =
                    assertInstanceOf(CacheNamespaceSupport.NamespacedKey.class, keyCaptor.getValue());
            assertEquals("ling-a", namespacedKey.getLingId());
            assertEquals("users", namespacedKey.getCacheName());
            assertEquals("user:1", namespacedKey.getRawKey());
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
        @DisplayName("getNativeCache 应保持保守策略并按 WRITE 鉴权")
        void shouldUseWriteAccessForNativeCacheHandle() {
            Cache target = mock(Cache.class);
            PermissionService permissionService = mock(PermissionService.class);
            Object nativeCache = new Object();
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.WRITE)).thenReturn(true);
            when(target.getNativeCache()).thenReturn(nativeCache);

            LingCallContext.setLingId("ling-a");
            when(target.getName()).thenReturn("users");
            LingSpringCacheProxy proxy = new LingSpringCacheProxy(target, permissionService);

            assertSame(nativeCache, proxy.getNativeCache());
            verify(permissionService).isAllowed("ling-a", "cache:local", AccessType.WRITE);
            verify(permissionService).audit("ling-a", "cache:local", "getNativeCache", true);
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
            CacheNamespaceSupport.NamespacedKey namespacedKey =
                    assertInstanceOf(CacheNamespaceSupport.NamespacedKey.class, keyCaptor.getValue());
            assertEquals("ling-a", namespacedKey.getLingId());
            assertEquals("users", namespacedKey.getCacheName());
            assertEquals("user:1", namespacedKey.getRawKey());
        }
    }
}
