package com.lingframe.plugin.cache.proxy;

import com.lingframe.api.context.PluginContextHolder;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.LoadingCache;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

public class LingCacheProxy<K, V> {

    private final Cache<K, V> target;
    private final PermissionService permissionService;

    public LingCacheProxy(Cache<K, V> target, PermissionService permissionService) {
        this.target = target;
        this.permissionService = permissionService;
    }

    private void checkPermission(String operation) {
        String callerPluginId = PluginContextHolder.get();
        if (callerPluginId == null) return;

        boolean allowed = permissionService.isAllowed(callerPluginId, "cache:local", AccessType.WRITE);
        permissionService.audit(callerPluginId, "cache:local", operation, allowed);

        if (!allowed) {
            throw new PermissionDeniedException("Plugin [" + callerPluginId + "] denied access to local cache operation: " + operation);
        }
    }

    // 基本操作
    public V getIfPresent(K key) {
        checkPermission("getIfPresent");
        return target.getIfPresent(key);
    }

    public void put(K key, V value) {
        checkPermission("put");
        target.put(key, value);
    }

    public void invalidate(K key) {
        checkPermission("invalidate");
        target.invalidate(key);
    }

    public void invalidateAll(Iterable<K> keys) {
        checkPermission("invalidateAll");
        target.invalidateAll(keys);
    }

    public void invalidateAll() {
        checkPermission("invalidateAll");
        target.invalidateAll();
    }

    public long estimatedSize() {
        checkPermission("estimatedSize");
        return target.estimatedSize();
    }

    public ConcurrentMap<K, V> asMap() {
        checkPermission("asMap");
        return target.asMap();
    }

    public Cache<K, V> getTarget() {
        return target;
    }

    // LoadingCache特有的操作
    public V get(K key, Function<? super K, ? extends V> mappingFunction) {
        checkPermission("get");
        return target.get(key, mappingFunction);
    }

    public CompletableFuture<V> getAsync(K key, Function<? super K, ? extends V> mappingFunction) {
        checkPermission("getAsync");
        V value = target.get(key, mappingFunction);
        return CompletableFuture.completedFuture(value);
    }

    public Map<K, V> getAllPresent(Iterable<K> keys) {
        checkPermission("getAllPresent");
        return target.getAllPresent(keys);
    }
}