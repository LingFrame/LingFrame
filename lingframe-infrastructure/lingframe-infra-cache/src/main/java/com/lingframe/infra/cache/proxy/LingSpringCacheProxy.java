package com.lingframe.infra.cache.proxy;

import com.lingframe.api.context.LingContextHolder;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.concurrent.Callable;

/**
 * 通用缓存代理 (适配 Caffeine, Redis, Ehcache)
 * 职责：拦截 put/get/evict 操作进行审计或流控
 */
@Slf4j
@RequiredArgsConstructor
public class LingSpringCacheProxy implements Cache {

    private final Cache target;

    private final PermissionService permissionService;

    private void checkPermission(String operation) {
        String callerLingId = LingContextHolder.get();
        if (callerLingId == null) return;

        boolean allowed = permissionService.isAllowed(callerLingId, "cache:local", AccessType.WRITE);
        permissionService.audit(callerLingId, "cache:local", operation, allowed);

        if (!allowed) {
            throw new PermissionDeniedException("Ling [" + callerLingId + "] denied access to local cache operation: " + operation);
        }
    }

    @Override
    public String getName() {
        checkPermission("get");
        return target.getName();
    }

    @Override
    public Object getNativeCache() {
        checkPermission("getNativeCache");
        return target.getNativeCache();
    }

    @Override
    public ValueWrapper get(@NonNull Object key) {
        checkPermission("get");
        return target.get(key);
    }

    @Override
    public <T> T get(@NonNull Object key, Class<T> type) {
        checkPermission("get");
        return target.get(key, type);
    }

    @Override
    public <T> T get(@NonNull Object key, @NonNull Callable<T> valueLoader) {
        checkPermission("get");
        return target.get(key, valueLoader);
    }

    @Override
    public void put(@NonNull Object key, @NonNull Object value) {
        checkPermission("put");
        target.put(key, value);
    }

    @Override
    public void evict(@NonNull Object key) {
        checkPermission("evict");
        target.evict(key);
    }

    @Override
    public void clear() {
        checkPermission("clear");
        target.clear();
    }

    @Override
    public @Nullable ValueWrapper putIfAbsent(@NonNull Object key, @Nullable Object value) {
        checkPermission("putIfAbsent");
        return target.putIfAbsent(key, value);
    }

    @Override
    public boolean evictIfPresent(@NonNull Object key) {
        checkPermission("evictIfPresent");
        return target.evictIfPresent(key);
    }

    @Override
    public boolean invalidate() {
        checkPermission("invalidate");
        return target.invalidate();
    }

}
