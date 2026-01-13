package com.lingframe.infra.cache.proxy;

import com.lingframe.api.context.PluginContextHolder;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 通用缓存代理 (适配 Caffeine, Redis, Ehcache)
 * 职责：拦截 put/get/evict 操作进行审计或流控
 */
@Slf4j
@RequiredArgsConstructor
public class LingSpringCacheProxy implements Cache {

    private final Cache target;

    private final PermissionService permissionService;

    private void checkPermission(String operation, AccessType accessType) {
        String callerPluginId = PluginContextHolder.get();
        if (callerPluginId == null) {
            // 检查是否启用了宿主治理
            if (permissionService.isHostGovernanceEnabled()) {
                throw new PermissionDeniedException(
                        "Access Denied: Host governance is enabled but no context provided for cache operation: "
                                + operation);
            }
            // 宿主治理关闭：默认放行
            return;
        }

        boolean allowed = permissionService.isAllowed(callerPluginId, "cache:local", accessType);
        permissionService.audit(callerPluginId, "cache:local", operation, allowed);

        if (!allowed) {
            throw new PermissionDeniedException(
                    "Plugin [" + callerPluginId + "] denied access to local cache operation: " + operation);
        }
    }

    @Override
    public String getName() {
        // getName 是只读元数据操作，不需要权限检查
        return target.getName();
    }

    @Override
    public Object getNativeCache() {
        // getNativeCache 返回原始缓存，需要 EXECUTE 权限（危险操作）
        checkPermission("getNativeCache", AccessType.EXECUTE);
        return target.getNativeCache();
    }

    @Override
    public ValueWrapper get(@NonNull Object key) {
        checkPermission("get", AccessType.READ);
        return target.get(key);
    }

    @Override
    public <T> T get(@NonNull Object key, Class<T> type) {
        checkPermission("get", AccessType.READ);
        return target.get(key, type);
    }

    @Override
    public <T> T get(@NonNull Object key, @NonNull Callable<T> valueLoader) {
        // get with valueLoader 可能会写入缓存，需要 WRITE 权限
        checkPermission("get", AccessType.WRITE);
        return target.get(key, valueLoader);
    }

    @Override
    public void put(@NonNull Object key, @NonNull Object value) {
        checkPermission("put", AccessType.WRITE);
        target.put(key, value);
    }

    @Override
    public void evict(@NonNull Object key) {
        checkPermission("evict", AccessType.WRITE);
        target.evict(key);
    }

    @Override
    public void clear() {
        checkPermission("clear", AccessType.WRITE);
        target.clear();
    }

    @Override
    public @Nullable CompletableFuture<?> retrieve(@NonNull Object key) {
        checkPermission("retrieve", AccessType.READ);
        return target.retrieve(key);
    }

    @Override
    public <T> CompletableFuture<T> retrieve(@NonNull Object key, @NonNull Supplier<CompletableFuture<T>> valueLoader) {
        // retrieve with valueLoader 可能会写入缓存，需要 WRITE 权限
        checkPermission("retrieve", AccessType.WRITE);
        return target.retrieve(key, valueLoader);
    }

    @Override
    public @Nullable ValueWrapper putIfAbsent(@NonNull Object key, @Nullable Object value) {
        checkPermission("putIfAbsent", AccessType.WRITE);
        return target.putIfAbsent(key, value);
    }

    @Override
    public boolean evictIfPresent(@NonNull Object key) {
        checkPermission("evictIfPresent", AccessType.WRITE);
        return target.evictIfPresent(key);
    }

    @Override
    public boolean invalidate() {
        checkPermission("invalidate", AccessType.WRITE);
        return target.invalidate();
    }

}
