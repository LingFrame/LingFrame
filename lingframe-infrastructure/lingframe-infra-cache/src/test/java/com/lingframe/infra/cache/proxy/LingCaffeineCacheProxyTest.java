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

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
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
            when(target.getIfPresent("user:1")).thenReturn("tom");

            LingCallContext.setLingId("ling-a");
            LingCaffeineCacheProxy<String, String> proxy = new LingCaffeineCacheProxy<>(target, permissionService);

            assertEquals("tom", proxy.getIfPresent("user:1"));
            verify(permissionService).isAllowed("ling-a", "cache:local", AccessType.READ);
            verify(permissionService).audit("ling-a", "cache:local", "getIfPresent", true);
        }

        @Test
        @DisplayName("带加载函数的 get 应按 WRITE 鉴权")
        void shouldUseWriteAccessForLoadingGet() {
            Cache<String, String> target = mockStringCache();
            PermissionService permissionService = mock(PermissionService.class);
            Function<String, String> loader = key -> "tom";
            when(permissionService.isAllowed("ling-a", "cache:local", AccessType.WRITE)).thenReturn(true);
            when(target.get(eq("user:1"), same(loader))).thenReturn("tom");

            LingCallContext.setLingId("ling-a");
            LingCaffeineCacheProxy<String, String> proxy = new LingCaffeineCacheProxy<>(target, permissionService);

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
            LingCaffeineCacheProxy<String, String> proxy = new LingCaffeineCacheProxy<>(target, permissionService);

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
            LingCaffeineCacheProxy<String, String> proxy = new LingCaffeineCacheProxy<>(target, permissionService);

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
}
