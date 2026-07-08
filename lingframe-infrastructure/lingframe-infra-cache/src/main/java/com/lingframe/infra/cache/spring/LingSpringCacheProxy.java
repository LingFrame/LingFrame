package com.lingframe.infra.cache.spring;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.infra.cache.proxy.CacheNamespaceSupport;
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
    private final String cacheName;
    private final PermissionService permissionService;

    public LingSpringCacheProxy(Cache target, PermissionService permissionService) {
        this(target, target == null ? "local-cache" : target.getName(), permissionService);
    }

    private void checkPermission(String operation, AccessType accessType) {
        String callerLingId = LingCallContext.getLingId();
        if (callerLingId == null) return;

        boolean allowed = permissionService.isAllowed(callerLingId, "cache:local", accessType);
        permissionService.audit(callerLingId, "cache:local", operation, allowed);

        if (!allowed) {
            throw new PermissionDeniedException("Ling [" + callerLingId + "] denied access to local cache operation: " + operation);
        }
    }

    @Override
    public String getName() {
        checkPermission("getName", AccessType.READ);
        return target.getName();
    }

    @Override
    public Object getNativeCache() {
        // 暴露原生缓存句柄会绕过权限和命名空间隔离，拒绝暴露
        throw new UnsupportedOperationException(
                "getNativeCache() is not supported through governance proxy to prevent bypass");
    }

    @Override
    public ValueWrapper get(@NonNull Object key) {
        checkPermission("get", AccessType.READ);
        return target.get(CacheNamespaceSupport.namespaceKey(cacheName, key));
    }

    @Override
    public <T> T get(@NonNull Object key, Class<T> type) {
        checkPermission("get", AccessType.READ);
        return target.get(CacheNamespaceSupport.namespaceKey(cacheName, key), type);
    }

    @Override
    public <T> T get(@NonNull Object key, @NonNull Callable<T> valueLoader) {
        // valueLoader 可能在缓存缺失时写入新值，因此按 WRITE 治理。
        checkPermission("get", AccessType.WRITE);
        return target.get(CacheNamespaceSupport.namespaceKey(cacheName, key), valueLoader);
    }

    @Override
    public void put(@NonNull Object key, @NonNull Object value) {
        checkPermission("put", AccessType.WRITE);
        target.put(CacheNamespaceSupport.namespaceKey(cacheName, key), value);
    }

    @Override
    public void evict(@NonNull Object key) {
        checkPermission("evict", AccessType.WRITE);
        target.evict(CacheNamespaceSupport.namespaceKey(cacheName, key));
    }

    @Override
    public void clear() {
        checkPermission("clear", AccessType.WRITE);
        // Spring Cache 无 key 枚举 API，无法按 lingId 精确清理；
        // 灵元调用 clear 会清空所有灵元缓存，拒绝
        String callerLingId = LingCallContext.getLingId();
        if (callerLingId != null) {
            throw new UnsupportedOperationException(
                    "clear() is not supported for ling; use evict(key) per entry");
        }
        target.clear();
    }

    @Override
    public @Nullable ValueWrapper putIfAbsent(@NonNull Object key, @Nullable Object value) {
        checkPermission("putIfAbsent", AccessType.WRITE);
        return target.putIfAbsent(CacheNamespaceSupport.namespaceKey(cacheName, key), value);
    }

    @Override
    public boolean evictIfPresent(@NonNull Object key) {
        checkPermission("evictIfPresent", AccessType.WRITE);
        return target.evictIfPresent(CacheNamespaceSupport.namespaceKey(cacheName, key));
    }

    @Override
    public boolean invalidate() {
        checkPermission("invalidate", AccessType.WRITE);
        // 与 clear() 同理，灵元调用会清空所有灵元缓存，拒绝
        String callerLingId = LingCallContext.getLingId();
        if (callerLingId != null) {
            throw new UnsupportedOperationException(
                    "invalidate() is not supported for ling; use evictIfPresent(key) per entry");
        }
        return target.invalidate();
    }

}
