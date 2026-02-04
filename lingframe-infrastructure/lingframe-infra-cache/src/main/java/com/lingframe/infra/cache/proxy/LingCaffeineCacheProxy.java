package com.lingframe.infra.cache.proxy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Policy;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.errorprone.annotations.CompatibleWith;
import com.lingframe.api.context.PluginContextHolder;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import lombok.RequiredArgsConstructor;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

@RequiredArgsConstructor
public class LingCaffeineCacheProxy<K, V> implements Cache<K, V> {

    private final Cache<K, V> target;
    private final PermissionService permissionService;

    private void checkPermission(String operation) {
        String callerPluginId = PluginContextHolder.get();
        if (callerPluginId == null) return;

        boolean allowed = permissionService.isAllowed(callerPluginId, "cache:local", AccessType.WRITE);
        permissionService.audit(callerPluginId, "cache:local", operation, allowed);

        if (!allowed) {
            throw new PermissionDeniedException("Plugin [" + callerPluginId + "] denied access to local cache operation: " + operation);
        }
    }

    @Override
    public @Nullable V getIfPresent(@CompatibleWith("K") @NonNull Object key) {
        checkPermission("getIfPresent");
        return target.getIfPresent(key);
    }

    @Override
    public V get(K key, @NonNull Function<? super K, ? extends V> mappingFunction) {
        checkPermission("get");
        return target.get(key, mappingFunction);
    }

    @Override
    public Map<K, V> getAllPresent(@NonNull Iterable<@NonNull ?> keys) {
        checkPermission("getAllPresent");
        return target.getAllPresent(keys);
    }

    @Override
    public Map<K, V> getAll(@NonNull Iterable<? extends K> keys,
                            @NonNull Function<Iterable<? extends @NonNull K>, @NonNull Map<K, V>> mappingFunction) {
        checkPermission("getAll");
        return target.getAll(keys, mappingFunction);
    }

    @Override
    public void put(K key, @NonNull V value) {
        checkPermission("put");
        target.put(key, value);
    }

    @Override
    public void putAll(@NonNull Map<? extends K, ? extends V> map) {
        checkPermission("putAll");
        target.putAll(map);
    }

    @Override
    public void invalidate(@CompatibleWith("K") @NonNull Object key) {
        checkPermission("invalidate");
        target.invalidate(key);
    }

    @Override
    public void invalidateAll(@NonNull Iterable<@NonNull ?> keys) {
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