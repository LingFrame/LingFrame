package com.lingframe.infra.cache.proxy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Policy;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.lingframe.api.context.LingContextHolder;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;

import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Caffeine Cache 治理代理
 * <p>
 * 内部使用原始类型 Cache 委托，以同时兼容 Caffeine 2.x 和 3.x 的泛型签名变化。
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public class LingCaffeineCacheProxy<K, V> implements Cache<K, V> {

    private final Cache target;
    private final PermissionService permissionService;

    public LingCaffeineCacheProxy(Cache<K, V> typedTarget, PermissionService permissionService) {
        this.target = typedTarget;
        this.permissionService = permissionService;
    }

    private void checkPermission(String operation) {
        String callerLingId = LingContextHolder.get();
        if (callerLingId == null)
            return;

        boolean allowed = permissionService.isAllowed(callerLingId, "cache:local", AccessType.WRITE);
        permissionService.audit(callerLingId, "cache:local", operation, allowed);

        if (!allowed) {
            throw new PermissionDeniedException(
                    "Ling [" + callerLingId + "] denied access to local cache operation: " + operation);
        }
    }

    @Override
    public V getIfPresent(Object key) {
        checkPermission("getIfPresent");
        return (V) target.getIfPresent(key);
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> mappingFunction) {
        checkPermission("get");
        return (V) target.get(key, mappingFunction);
    }

    @Override
    public Map<K, V> getAllPresent(Iterable keys) {
        checkPermission("getAllPresent");
        return target.getAllPresent(keys);
    }

    @Override
    public Map<K, V> getAll(Iterable keys, Function mappingFunction) {
        checkPermission("getAll");
        return target.getAll(keys, mappingFunction);
    }

    @Override
    public void put(K key, V value) {
        checkPermission("put");
        target.put(key, value);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
        checkPermission("putAll");
        target.putAll(map);
    }

    @Override
    public void invalidate(Object key) {
        checkPermission("invalidate");
        target.invalidate(key);
    }

    @Override
    public void invalidateAll(Iterable keys) {
        checkPermission("invalidateAll");
        target.invalidateAll(keys);
    }

    @Override
    public void invalidateAll() {
        checkPermission("invalidateAll");
        target.invalidateAll();
    }

    @Override
    public long estimatedSize() {
        checkPermission("estimatedSize");
        return target.estimatedSize();
    }

    @Override
    public CacheStats stats() {
        checkPermission("stats");
        return target.stats();
    }

    @Override
    public ConcurrentMap<K, V> asMap() {
        checkPermission("asMap");
        return target.asMap();
    }

    @Override
    public void cleanUp() {
        checkPermission("cleanUp");
        target.cleanUp();
    }

    @Override
    public Policy<K, V> policy() {
        checkPermission("policy");
        return target.policy();
    }
}