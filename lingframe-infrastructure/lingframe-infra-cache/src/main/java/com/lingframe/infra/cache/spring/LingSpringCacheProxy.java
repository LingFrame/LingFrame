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

        boolean allowed = permissionService.isAllowed(callerLingId, "cache:local", accessType);
        permissionService.audit(callerLingId, "cache:local", operation, allowed);

        if (!allowed) {
            throw new PermissionDeniedException("Ling [" + callerLingId + "] denied access to local cache operation: " + operation);
        }
    }

    @Override
    public String getName() {
        // 元数据方法豁免鉴权：Spring 内部（如 CacheManager.getCacheNames、日志、抽象层）会频繁调用 getName()，
        // 若走鉴权会在无上下文或治理开启时被破坏。直接委托 target，与 Caffeine 代理行为对齐。
        return target.getName();
    }

    @Override
    public Object getNativeCache() {
        // 灵核（无上下文）放行：Spring 内部探测（CacheManager 类型检查、Actuator 指标等）无 LingContext。
        // 灵元拒绝：暴露原生句柄会绕过权限与命名空间隔离，与 asMap()/policy() 拒绝暴露原生可变视图一致。
        String callerLingId = LingCallContext.getLingId();
        if (callerLingId == null) {
            return target.getNativeCache();
        }
        // 灵元拒绝时同样审计，与 clear()/invalidate() 行为对齐
        permissionService.audit(callerLingId, "cache:nativeCache", "getNativeCache", false);
        throw new PermissionDeniedException(callerLingId, "cache:nativeCache");
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
        // clear 会清空整个缓存（跨灵元共享），先决定行为再审计，避免"审计允许但实际拒绝"的不一致。
        // 灵核（无 lingId）特权放行——即使治理开启也允许，因为灵核负责全局运维；
        // 灵元一律拒绝（会清空其他灵元缓存），审计记录 allowed=false，capability 统一为 "cache:clear"。
        String callerLingId = LingCallContext.getLingId();
        if (callerLingId == null) {
            // 灵核特权：放行但记录审计（避免特权路径绕过审计），不再走 checkPermission（避免治理开启时被 fail-closed 拦截）
            permissionService.audit("LINGCORE", "cache:clear", "clear", true);
            target.clear();
            return;
        }
        permissionService.audit(callerLingId, "cache:clear", "clear", false);
        throw new PermissionDeniedException(callerLingId, "cache:clear");
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
        // 与 clear() 同理：invalidate 会清空整个缓存，先决定行为再审计。
        // 灵核（无 lingId）特权放行——即使治理开启也允许；灵元一律拒绝，capability 统一为 "cache:invalidate"。
        String callerLingId = LingCallContext.getLingId();
        if (callerLingId == null) {
            // 灵核特权：放行但记录审计（避免特权路径绕过审计），不再走 checkPermission（避免治理开启时被 fail-closed 拦截）
            permissionService.audit("LINGCORE", "cache:invalidate", "invalidate", true);
            return target.invalidate();
        }
        permissionService.audit(callerLingId, "cache:invalidate", "invalidate", false);
        throw new PermissionDeniedException(callerLingId, "cache:invalidate");
    }

}
