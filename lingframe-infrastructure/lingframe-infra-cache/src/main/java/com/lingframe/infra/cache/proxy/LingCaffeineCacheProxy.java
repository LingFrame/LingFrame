package com.lingframe.infra.cache.proxy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Policy;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.constant.LingCoreConstants;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Caffeine Cache 治理代理
 * <p>
 * 内部使用原始类型 Cache 委托，以同时兼容 Caffeine 2.x 和 3.x 的泛型签名变化。
 */
@Slf4j
@SuppressWarnings({ "unchecked", "rawtypes" })
public class LingCaffeineCacheProxy<K, V> implements Cache<K, V> {

    private final Cache target;
    private final String cacheName;
    private final PermissionService permissionService;

    public LingCaffeineCacheProxy(Cache<K, V> typedTarget, PermissionService permissionService) {
        this(typedTarget, "local-cache", permissionService);
    }

    public LingCaffeineCacheProxy(Cache<K, V> typedTarget, String cacheName, PermissionService permissionService) {
        this.target = typedTarget;
        this.cacheName = cacheName;
        this.permissionService = permissionService;
    }

    private void checkPermission(String operation, AccessType accessType) {
        String callerLingId = LingCallContext.getLingId();
        if (callerLingId == null) {
            // 与 SQL proxy 行为对齐：灵核治理开启时拒绝无上下文操作（fail-closed），
            // 关闭时默认放行（LINGCORE Privilege）。
            if (permissionService.isLingCoreGovernanceEnabled()) {
                log.error(
                        "Security Alert: cache operation without LingContext (LINGCORE governance ENABLED). Operation: {}",
                        operation);
                throw new PermissionDeniedException(
                        "Access Denied: LINGCORE governance is enabled but no context provided for cache operation: "
                                + operation);
            }
            log.debug("Cache operation without LingContext (LINGCORE governance disabled). ALLOWED. Operation: {}",
                    operation);
            return;
        }

        boolean allowed = permissionService.isAllowed(callerLingId, Capabilities.CACHE_LOCAL, accessType);
        permissionService.audit(callerLingId, Capabilities.CACHE_LOCAL, operation, allowed);

        if (!allowed) {
            throw new PermissionDeniedException(
                    "Ling [" + callerLingId + "] denied access to local cache operation: " + operation);
        }
    }

    @Override
    public V getIfPresent(Object key) {
        checkPermission("getIfPresent", AccessType.READ);
        return (V) target.getIfPresent(CacheNamespaceSupport.namespaceKey(cacheName, key));
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> mappingFunction) {
        // mappingFunction 可能在缓存缺失时回填数据，因此按 WRITE 要求治理。
        checkPermission("get", AccessType.WRITE);
        Object namespacedKey = CacheNamespaceSupport.namespaceKey(cacheName, key);
        return (V) target.get(namespacedKey, ignored -> mappingFunction.apply(key));
    }

    @Override
    public Map<K, V> getAllPresent(Iterable keys) {
        checkPermission("getAllPresent", AccessType.READ);
        return CacheNamespaceSupport.denamespaceMapKeys(target.getAllPresent(
                CacheNamespaceSupport.namespaceKeys(cacheName, keys)));
    }

    @Override
    public Map<K, V> getAll(Iterable keys, Function mappingFunction) {
        // 批量加载同样可能触发缓存写入，不能按纯 READ 处理。
        checkPermission("getAll", AccessType.WRITE);
        List<Object> namespacedKeys = CacheNamespaceSupport.namespaceKeys(cacheName, keys);
        Map<K, V> loaded = target.getAll(namespacedKeys, namespacedMissingKeys -> {
            // Caffeine 把缺失的 NamespacedKey 集合回调给 mappingFunction。
            // 先还原成原始 key 交给用户 loader，再把 loader 返回的 Map key 重新命名空间化，
            // 保证回填 Map 的 key 与传入的 missing keys(NamespacedKey) 在 equals/hashCode 上完全一致。
            // 否则 Caffeine 内部 loaded.get(NK) 会因 key 不一致而返回 null，导致 getAll 永远返回空。
            List<Object> rawMissingKeys = new ArrayList<>();
            for (Object namespacedKey : (Iterable<?>) namespacedMissingKeys) {
                rawMissingKeys.add(CacheNamespaceSupport.denamespaceKey(namespacedKey));
            }
            Map<Object, V> namespacedLoaded = new LinkedHashMap<>();
            Map<?, V> rawLoaded = (Map<?, V>) mappingFunction.apply(rawMissingKeys);
            if (rawLoaded != null) {
                for (Map.Entry<?, V> entry : rawLoaded.entrySet()) {
                    Object reNamespacedKey = CacheNamespaceSupport.namespaceKey(cacheName, entry.getKey());
                    namespacedLoaded.put(reNamespacedKey, entry.getValue());
                }
            }
            return namespacedLoaded;
        });
        return CacheNamespaceSupport.denamespaceMapKeys(loaded);
    }

    @Override
    public void put(K key, V value) {
        checkPermission("put", AccessType.WRITE);
        target.put(CacheNamespaceSupport.namespaceKey(cacheName, key), value);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
        checkPermission("putAll", AccessType.WRITE);
        Map<Object, Object> namespacedMap = new LinkedHashMap<>();
        for (Map.Entry<? extends K, ? extends V> entry : map.entrySet()) {
            namespacedMap.put(CacheNamespaceSupport.namespaceKey(cacheName, entry.getKey()), entry.getValue());
        }
        target.putAll(namespacedMap);
    }

    @Override
    public void invalidate(Object key) {
        checkPermission("invalidate", AccessType.WRITE);
        target.invalidate(CacheNamespaceSupport.namespaceKey(cacheName, key));
    }

    @Override
    public void invalidateAll(Iterable keys) {
        checkPermission("invalidateAll", AccessType.WRITE);
        target.invalidateAll(CacheNamespaceSupport.namespaceKeys(cacheName, keys));
    }

    @Override
    public void invalidateAll() {
        String callerLingId = LingCallContext.getLingId();
        if (callerLingId == null) {
            // 灵核特权：放行但记录审计（避免特权路径绕过审计），直接全清，不走 checkPermission（与 LingSpringCacheProxy.clear 对齐，
            // 避免治理开启时被 fail-closed 拦截导致灵核无法运维清理）
            permissionService.audit(LingCoreConstants.LINGCORE_LING_ID, Capabilities.CACHE_INVALIDATE_ALL, "invalidateAll", true);
            target.invalidateAll();
            return;
        }
        checkPermission("invalidateAll", AccessType.WRITE);
        // 灵元：仅清当前灵元的 namespaced key，避免清空其他灵元缓存
        target.asMap().keySet().removeIf(key ->
                CacheNamespaceSupport.isNamespacedKey(key)
                        && callerLingId.equals(CacheNamespaceSupport.extractLingId(key)));
    }

    @Override
    public long estimatedSize() {
        checkPermission("estimatedSize", AccessType.READ);
        return target.estimatedSize();
    }

    @Override
    public CacheStats stats() {
        checkPermission("stats", AccessType.READ);
        return target.stats();
    }

    @Override
    public ConcurrentMap<K, V> asMap() {
        // asMap 暴露原生可变视图会绕过权限和命名空间隔离，拒绝暴露
        throw new UnsupportedOperationException(
                "asMap() is not supported through governance proxy to prevent bypass");
    }

    @Override
    public void cleanUp() {
        checkPermission("cleanUp", AccessType.WRITE);
        target.cleanUp();
    }

    @Override
    public Policy<K, V> policy() {
        // policy 暴露底层策略句柄可修改 eviction 策略，拒绝暴露
        throw new UnsupportedOperationException(
                "policy() is not supported through governance proxy to prevent bypass");
    }
}
