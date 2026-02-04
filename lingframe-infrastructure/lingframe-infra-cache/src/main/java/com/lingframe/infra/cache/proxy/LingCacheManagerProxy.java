package com.lingframe.infra.cache.proxy;

import com.lingframe.api.security.PermissionService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Collection;

@RequiredArgsConstructor
public class LingCacheManagerProxy implements CacheManager {

    private final CacheManager target;
    private final PermissionService permissionService;

    @Override
    public Cache getCache(@NonNull String name) {
        Cache cache = target.getCache(name);
        if (cache == null) return null;
        // 🔥 关键：无论底层是 RedisCache 还是 CaffeineCache，统一套上治理壳
        // 这里的 LingCacheProxy 是针对 org.springframework.cache.Cache 接口的通用代理
        return new LingSpringCacheProxy(cache, permissionService);
    }


    @Override
    public Collection<String> getCacheNames() {
        return target.getCacheNames();
    }
}