package com.lingframe.infra.cache.spring;

import com.lingframe.api.security.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("灵元缓存管理器代理测试")
class LingCacheManagerProxyTest {

    @Test
    @DisplayName("获取缓存时应返回带治理包装的缓存代理")
    void shouldReturnWrappedCacheProxy() {
        CacheManager target = mock(CacheManager.class);
        Cache cache = mock(Cache.class);
        PermissionService permissionService = mock(PermissionService.class);
        when(target.getCache("users")).thenReturn(cache);

        LingCacheManagerProxy proxy = new LingCacheManagerProxy(target, permissionService);

        Cache wrapped = proxy.getCache("users");

        assertNotNull(wrapped);
        assertInstanceOf(LingSpringCacheProxy.class, wrapped);
        verify(target).getCache("users");
    }

    @Test
    @DisplayName("底层缓存不存在时应返回 null")
    void shouldReturnNullWhenUnderlyingCacheIsMissing() {
        CacheManager target = mock(CacheManager.class);
        PermissionService permissionService = mock(PermissionService.class);
        when(target.getCache("missing")).thenReturn(null);

        LingCacheManagerProxy proxy = new LingCacheManagerProxy(target, permissionService);

        assertNull(proxy.getCache("missing"));
    }
}
