package com.lingframe.starter.resource;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;

/**
 * 清理 JDK {@link java.lang.reflect.Proxy} 内部静态缓存。
 * <p>
 * {@code Proxy.proxyClassCache}（WeakCache）以 ClassLoader 为外层 key，
 * 其 CacheValue 持有生成的代理 Class 强引用，而代理 Class 持有 ClassLoader 引用，
 * 形成阻止 ClassLoader GC 的强引用链。
 * <p>
 * 现有清理器（CGLIB、SpringFactoriesLoader 等）均未覆盖此缓存，
 * 导致 Spring AOP 代理（如 {@code javax.validation.Validator} 的 AOP 代理）的
 * ClassLoader 在灵元卸载后无法被 GC 回收。
 * <p>
 * 发现来源：{@code SpringLingContainerUnloadRegressionTest} 诊断出的持有链：
 * <pre>
 * Proxy.proxyClassCache
 *   → WeakCache.map → CacheKey(referent=ClassLoader)
 *   → subMap → CacheValue
 *   → $ProxyXX.class → ClassLoader
 * </pre>
 */
@Slf4j
final class JdkProxyCacheCleaner {

    void clear(String lingId, ClassLoader lingClassLoader) {
        if (lingClassLoader == null) {
            return;
        }
        try {
            Field cacheField = Proxy.class.getDeclaredField("proxyClassCache");
            cacheField.setAccessible(true);
            Object cache = cacheField.get(null);
            if (cache == null) {
                log.trace("[{}] Proxy.proxyClassCache is null", lingId);
                return;
            }

            // WeakCache 内部结构：map (ConcurrentMap) -> subMap -> Factory
            Field mapField = SpringCleanupSupport.findFieldInHierarchy(cache.getClass(), "map");
            if (mapField == null) {
                log.trace("[{}] WeakCache.map field does not exist", lingId);
                return;
            }
            mapField.setAccessible(true);
            Object map = mapField.get(cache);
            if (!(map instanceof ConcurrentMap)) {
                return;
            }
            ConcurrentMap<?, ?> cmap = (ConcurrentMap<?, ?>) map;

            int removed = 0;
            for (Object key : cmap.keySet()) {
                if (key instanceof Reference) {
                    Object referent = ((Reference<?>) key).get();
                    if (referent == lingClassLoader) {
                        cmap.remove(key);
                        removed++;
                        log.debug("[{}] Cleared ClassLoader entry in Proxy.WeakCache", lingId);
                    }
                }
            }

            // 强制清理 stale entries（WeakCache 内部维护一个引用队列）
            try {
                Method expungeMethod = cache.getClass().getDeclaredMethod("expungeStaleEntries");
                expungeMethod.setAccessible(true);
                expungeMethod.invoke(cache);
            } catch (Exception ignored) {
                // 低版本 JDK 可能没有此方法，忽略
            }

            if (removed > 0) {
                log.info("[{}] Cleared {} Proxy.WeakCache entries", lingId, removed);
            }
        } catch (NoSuchFieldException e) {
            log.trace("[{}] Proxy.proxyClassCache field does not exist (JDK version difference)", lingId);
        } catch (Exception e) {
            log.debug("[{}] Failed to clear Proxy.WeakCache: {}", lingId, e.getMessage());
        }
    }
}
