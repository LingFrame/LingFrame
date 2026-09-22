package com.lingframe.starter.resource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.CachedIntrospectionResults;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.util.ReflectionUtils;

import java.beans.Introspector;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * 清理 Spring 框架公开 API 的静态缓存。
 * <p>
 * 这些缓存 key 是灵元加载的 Class，会阻止灵元 ClassLoader GC。
 * 包括：CachedIntrospectionResults / ReflectionUtils / AnnotationUtils /
 * SpringFactoriesLoader / ResolvableType / Property.annotationCache /
 * ResourceBundle / Micrometer TimedAnnotations / BridgeMethodResolver 等。
 * <p>
 * 共享 Spring 父委派下，清理是证据驱动、按点扩展的；新增清理点须有 dump/回归证据，
 * 并保持与现有 Cleaner 拆分一致。
 * <p>
 * 专用判断器 {@link #isPropertyRelatedToClassLoader} 和
 * {@link #isResolvableTypeRelated} 留在本类内部，不下沉到 Support。
 */
@Slf4j
final class SpringStaticCacheCleaner {

    /**
     * 注册扫描后有界清理：注解/反射 + MethodClassKey 相关 ConcurrentReferenceHashMap。
     * 由 {@link LingScanCachePurger} 在 Web 元数据提取完成后调用，缩短扫描污染窗口；
     * 不替代 unload 全量 {@link #clearStablePublicCaches}。
     */
    void purgeAnnotationCachesAfterScan(String lingId, ClassLoader lingClassLoader) {
        clearReflectionUtilsSelective(lingId, lingClassLoader);
        clearAnnotationUtilsSelective(lingId, lingClassLoader);
        clearMethodIntrospectorCaches(lingId, lingClassLoader);
        clearAopUtilsCaches(lingId, lingClassLoader);
    }

    /** cleanup 阶段：清理所有稳定公开缓存 */
    void clearStablePublicCaches(String lingId, ClassLoader lingClassLoader) {
        // 1. CachedIntrospectionResults — 所有版本都有的公开 API
        try {
            CachedIntrospectionResults.clearClassLoader(lingClassLoader);
            log.debug("[{}] Cleared CachedIntrospectionResults", lingId);
        } catch (Exception e) {
            log.debug("[{}] CachedIntrospectionResults cleanup failed: {}", lingId, e.getMessage());
        }

        // 2. ReflectionUtils
        clearReflectionUtilsSelective(lingId, lingClassLoader);

        // 3. AnnotationUtils
        clearAnnotationUtilsSelective(lingId, lingClassLoader);

        // 4. Micrometer TimedAnnotations
        clearMicrometerCaches(lingId, lingClassLoader);

        // 5. ResolvableType
        clearResolvableTypeSelective(lingId, lingClassLoader);

        // 6. JDK ResourceBundle
        try {
            ResourceBundle.clearCache(lingClassLoader);
            log.debug("[{}] Cleared ResourceBundle cache", lingId);
        } catch (Exception e) {
            log.debug("[{}] ResourceBundle cache cleanup failed: {}", lingId, e.getMessage());
        }

        // 7. java.beans.Introspector
        try {
            Introspector.flushCaches();
            log.debug("[{}] Flushed Introspector caches", lingId);
        } catch (Exception e) {
            log.debug("[{}] Introspector cache flush failed: {}", lingId, e.getMessage());
        }

        // 8. BeanAnnotationHelper（scopedProxyCache + beanNameCache，key 都是 Method）
        clearBeanAnnotationHelper(lingId, lingClassLoader);

        // 9. ClassUtils.cache（ConcurrentHashMap<ClassLoader, Map<Class,Class>>，key 是 ClassLoader）
        clearStaticMapByClassLoaderKey(lingId, lingClassLoader,
                "org.springframework.util.ClassUtils", "cache");

        // 10. GenericTypeResolver（map 字段，key 是灵元加载的 Class）
        try {
            int removed = SpringCleanupSupport.removeStaticMapEntries(
                    Class.forName("org.springframework.core.GenericTypeResolver"), lingClassLoader);
            if (removed > 0) {
                log.debug("[{}] GenericTypeResolver: removed {} entries", lingId, removed);
            }
        } catch (ClassNotFoundException ignored) {
            // 版本差异，类不存在
        } catch (Exception e) {
            log.debug("[{}] GenericTypeResolver cleanup failed: {}", lingId, e.getMessage());
        }

        // 11. Jackson2ObjectMapperBuilder.cache（key/value 关联灵元 ClassLoader）
        try {
            int removed = SpringCleanupSupport.removeStaticMapEntries(
                    Class.forName("org.springframework.http.converter.json.Jackson2ObjectMapperBuilder"),
                    lingClassLoader);
            if (removed > 0) {
                log.debug("[{}] Jackson2ObjectMapperBuilder: removed {} entries", lingId, removed);
            }
        } catch (ClassNotFoundException ignored) {
            // 版本差异，类不存在
        } catch (Exception e) {
            log.debug("[{}] Jackson2ObjectMapperBuilder cleanup failed: {}", lingId, e.getMessage());
        }

        // 12. MethodIntrospector / AopUtils：MethodClassKey + ConcurrentReferenceHashMap（SB3 dispatch 残留主因）
        clearMethodIntrospectorCaches(lingId, lingClassLoader);
        clearAopUtilsCaches(lingId, lingClassLoader);

        // 13. 其它常见 MethodClassKey / 元数据 ConcurrentReferenceHashMap 持有点
        clearKnownMethodClassKeyHosts(lingId, lingClassLoader);
    }

    /**
     * MethodIntrospector：部分版本无静态 cache（仅工具方法）；仍尝试清静态 Map。
     */
    private void clearMethodIntrospectorCaches(String lingId, ClassLoader lingClassLoader) {
        clearStaticMapsOnClass(lingId, lingClassLoader, "org.springframework.core.MethodIntrospector");
    }

    private void clearAopUtilsCaches(String lingId, ClassLoader lingClassLoader) {
        clearStaticMapsOnClass(lingId, lingClassLoader, "org.springframework.aop.support.AopUtils");
    }

    /**
     * 清理已知 MethodClassKey / ConcurrentReferenceHashMap 持有点。
     * <p>
     * heap 主因：{@code BridgeMethodResolver.cache}（key=MethodClassKey，CRHM Soft 边）
     * 钉住灵元 Class → ClassLoader。另含事务/缓存注解源的 fallback cache。
     */
    private void clearKnownMethodClassKeyHosts(String lingId, ClassLoader lingClassLoader) {
        // BridgeMethodResolver：dispatch 后最关键；选择性 remove 后 SoftEntryReference 仍可能拖住，
        // 对含灵元条目的 cache 做 clear + Soft 链 release（可按需重建，体量小）
        clearBridgeMethodResolverCache(lingId, lingClassLoader);
        String[] hosts = {
                "org.springframework.core.annotation.AnnotatedElementUtils",
                "org.springframework.transaction.interceptor.AbstractFallbackTransactionAttributeSource",
                "org.springframework.cache.interceptor.AbstractFallbackCacheOperationSource",
                "org.springframework.cache.jcache.interceptor.AbstractFallbackJCacheOperationSource",
                "org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory",
                "org.springframework.context.event.ApplicationListenerMethodAdapter",
                "org.springframework.web.method.HandlerMethod",
                "org.springframework.util.ReflectionUtils",
        };
        for (String className : hosts) {
            clearStaticMapsOnClass(lingId, lingClassLoader, className);
        }
    }

    /**
     * BridgeMethodResolver.cache = ConcurrentReferenceHashMap&lt;MethodClassKey, Method&gt;。
     * SoftEntryReference 软引用整个 Entry；System.gc 不保证清 Soft。
     * <p>
     * <b>同步排空顺序</b>（卸载契约，非靠下次 insert / 内存压力）：
     * <ol>
     *   <li>selective remove（MethodClassKey / Soft 关联灵元 CL）</li>
     *   <li>CRHM SoftEntryReference.release 深清</li>
     *   <li>仍有关联时 cache.clear() + purgeUnreferencedEntries（表可重建）</li>
     * </ol>
     */
    private void clearBridgeMethodResolverCache(String lingId, ClassLoader lingClassLoader) {
        try {
            Class<?> clazz = Class.forName("org.springframework.core.BridgeMethodResolver");
            Field cacheField = SpringCleanupSupport.findFieldInHierarchy(clazz, "cache");
            if (cacheField == null) {
                return;
            }
            cacheField.setAccessible(true);
            Object cacheObj = cacheField.get(null);
            if (!(cacheObj instanceof Map<?, ?>)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<Object, Object> cache = (Map<Object, Object>) cacheObj;
            int before = cache.size();
            int removed = SpringCleanupSupport.clearMapRelatedToClassLoader(cache, lingClassLoader);
            // Soft 边在 live 堆上常拖住 MethodClassKey；有关联条目时直接 clear（bridge 可重建）
            boolean anyRelatedLeft = false;
            try {
                for (Map.Entry<?, ?> e : cache.entrySet()) {
                    if (SpringCleanupSupport.isRelatedToClassLoader(e.getKey(), lingClassLoader)
                            || SpringCleanupSupport.isRelatedToClassLoader(e.getValue(), lingClassLoader)) {
                        anyRelatedLeft = true;
                        break;
                    }
                }
            } catch (Exception ignored) {
                anyRelatedLeft = !cache.isEmpty() && removed > 0;
            }
            if (anyRelatedLeft || removed > 0) {
                // 再深清一次 Soft 链
                SpringCleanupSupport.deepClearConcurrentReferenceHashMap(cache, lingClassLoader);
                try {
                    // 仍可能残留：整表 clear + purge（进程内 bridge 缓存可按需重建）
                    if (!cache.isEmpty() && removed > 0) {
                        cache.clear();
                        try {
                            Method purge = cache.getClass().getMethod("purgeUnreferencedEntries");
                            purge.invoke(cache);
                        } catch (Exception ignored) {
                            // ignore
                        }
                        log.info("[{}] BridgeMethodResolver.cache cleared (had {} entries, removed related={})",
                                lingId, before, removed);
                    } else if (removed > 0) {
                        log.info("[{}] BridgeMethodResolver.cache: removed {} related entries (size {} -> {})",
                                lingId, removed, before, cache.size());
                    }
                } catch (Exception e) {
                    log.debug("[{}] BridgeMethodResolver.cache clear failed: {}", lingId, e.getMessage());
                }
            }
        } catch (ClassNotFoundException ignored) {
            // ignore
        } catch (Exception e) {
            log.debug("[{}] BridgeMethodResolver cleanup failed: {}", lingId, e.getMessage());
        }
    }

    private void clearStaticMapsOnClass(String lingId, ClassLoader lingClassLoader, String className) {
        try {
            int removed = SpringCleanupSupport.removeStaticMapEntries(
                    Class.forName(className), lingClassLoader);
            if (removed > 0) {
                log.info("[{}] {}: removed {} static map entries", lingId, className, removed);
            }
        } catch (ClassNotFoundException ignored) {
            // version / optional module
        } catch (Exception e) {
            log.debug("[{}] {} cleanup failed: {}", lingId, className, e.getMessage());
        }
    }

    // ======================== ParameterNameDiscoverer（实例字段缓存） ========================

    /**
     * 清理 {@code LocalVariableTableParameterNameDiscoverer.parameterNamesCache}。
     * <p>
     * <b>这是 ClassLoader 泄漏的主要根因之一</b>：该缓存是实例字段（非 static），
     * key 是灵元加载的 {@code Class<?>}，被全局共享的
     * {@code DefaultParameterNameDiscoverer} 实例间接持有。
     * 卸载时不清理 → grab 的 Class 被 map key 持有 → ClassLoader 泄漏。
     * <p>
     * 因为是实例字段，{@code removeStaticMapEntries} 扫不到，必须通过 Spring context
     * 的 BeanFactory 遍历 singleton 深度查找。
     * <p>
     * MAT 实测引用链（Spring 5.3.31）：
     * <pre>
     * LingClassLoader ← GrabServiceImpl Class (parameterNamesCache map key)
     *   ← LocalVariableTableParameterNameDiscoverer.parameterNamesCache
     *     ← DefaultParameterNameDiscoverer.parameterNameDiscoverers (List)
     *       ← Spring BeanFactory singleton 图
     * </pre>
     */
    void clearParameterNameDiscovererCache(String lingId, ApplicationContext mainContext, ClassLoader lingClassLoader) {
        if (mainContext == null || lingClassLoader == null) {
            return;
        }
        int totalRemoved = 0;
        try {
            ConfigurableApplicationContext cac;
            try {
                cac = (ConfigurableApplicationContext) mainContext;
            } catch (ClassCastException e) {
                return;
            }
            org.springframework.beans.factory.config.ConfigurableListableBeanFactory bf = cac.getBeanFactory();
            // 遍历所有 singleton，深度查找 LVTPND 实例
            for (String name : bf.getSingletonNames()) {
                Object singleton;
                try {
                    singleton = bf.getSingleton(name);
                } catch (Exception ignored) {
                    continue;
                }
                if (singleton == null) continue;
                totalRemoved += findAndClearPNDInstance(lingId, singleton, lingClassLoader, new HashSet<>(), 0);
            }
            // 遍历 mergedBeanDefinitions（Bean 定义）——LVTPND 被 Bean 定义的 preparedConstructorArguments
            // → ConstructorDependencyDescriptor → ResolvableType → ... → MethodParameter
            // → DefaultParameterNameDiscoverer → parameterNameDiscoverers (List) → LVTPND 间接持有（15+ 层）
            try {
                Field mergedField = SpringCleanupSupport.findFieldInHierarchy(bf.getClass(), "mergedBeanDefinitions");
                if (mergedField != null) {
                    mergedField.setAccessible(true);
                    Object mergedObj = mergedField.get(bf);
                    if (mergedObj instanceof Map<?, ?>) {
                        for (Object bd : ((Map<?, ?>) mergedObj).values()) {
                            totalRemoved += findAndClearPNDInstance(lingId, bd, lingClassLoader, new HashSet<>(), 0);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[{}] Failed to scan mergedBeanDefinitions for PND: {}", lingId, e.getMessage());
            }
            // BeanFactory 自身也扫一遍
            totalRemoved += findAndClearPNDInstance(lingId, bf, lingClassLoader, new HashSet<>(), 0);
        } catch (Exception e) {
            log.debug("[{}] ParameterNameDiscoverer cache cleanup failed: {}", lingId, e.getMessage());
        }
        if (totalRemoved > 0) {
            log.info("[{}] LocalVariableTableParameterNameDiscoverer.parameterNamesCache: removed {} entries total",
                    lingId, totalRemoved);
        }
    }

    /**
     * 深度遍历对象图，找 {@code LocalVariableTableParameterNameDiscoverer} 实例并清理其
     * {@code parameterNamesCache} 中 key 属于 {@code lingClassLoader} 的条目。
     */
    private int findAndClearPNDInstance(String lingId, Object obj, ClassLoader lingClassLoader,
                                        Set<Integer> seen, int depth) {
        if (obj == null || depth > 15) {
            return 0;
        }
        int h = System.identityHashCode(obj);
        if (seen.contains(h)) {
            return 0;
        }
        seen.add(h);

        String className = obj.getClass().getName();
        // 目标：LocalVariableTableParameterNameDiscoverer
        if ("org.springframework.core.LocalVariableTableParameterNameDiscoverer".equals(className)) {
            return clearParameterNamesCacheField(lingId, obj, lingClassLoader);
        }

        // 跳过已知的大/不相关类型，避免深度递归
        if (obj instanceof String || obj instanceof Number || obj instanceof Boolean
                || obj instanceof Character || obj instanceof Class
                || obj instanceof Thread || obj instanceof ClassLoader) {
            return 0;
        }
        // 跳过 Spring context 自身（已通过 singleton 遍历覆盖）
        if (obj instanceof org.springframework.context.ApplicationContext) {
            return 0;
        }

        int removed = 0;
        // Map/Collection 浅扫 key（DefaultParameterNameDiscoverer 在 List 里）
        if (obj instanceof List) {
            for (Object e : (List<?>) obj) {
                removed += findAndClearPNDInstance(lingId, e, lingClassLoader, seen, depth + 1);
                if (removed > 0) break;
            }
            return removed;
        }
        if (obj instanceof Map) {
            for (Object v : ((Map<?, ?>) obj).values()) {
                removed += findAndClearPNDInstance(lingId, v, lingClassLoader, seen, depth + 1);
                if (removed > 0) break;
            }
            return removed;
        }

        // 普通对象字段
        Class<?> walk = obj.getClass();
        while (walk != null && walk != Object.class) {
            Field[] fields;
            try {
                fields = walk.getDeclaredFields();
            } catch (Throwable t) {
                break;
            }
            for (Field f : fields) {
                if (f.getType().isPrimitive() || Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object fv = f.get(obj);
                    removed += findAndClearPNDInstance(lingId, fv, lingClassLoader, seen, depth + 1);
                    if (removed > 0) break;
                } catch (Throwable ignored) {
                }
            }
            if (removed > 0) break;
            walk = walk.getSuperclass();
        }
        return removed;
    }

    /**
     * 清理 LocalVariableTableParameterNameDiscoverer.parameterNamesCache 实例字段。
     * key 是 {@code Class<?>}（灵元加载的类），value 是 {@code Map<Executable, String[]>}。
     */
    @SuppressWarnings("unchecked")
    private int clearParameterNamesCacheField(String lingId, Object pndInstance, ClassLoader lingClassLoader) {
        try {
            Field cacheField = SpringCleanupSupport.findFieldInHierarchy(pndInstance.getClass(), "parameterNamesCache");
            if (cacheField == null) {
                return 0;
            }
            cacheField.setAccessible(true);
            Object cacheObj = cacheField.get(pndInstance);
            if (!(cacheObj instanceof Map<?, ?>)) {
                return 0;
            }
            Map<Class<?>, ?> cache = (Map<Class<?>, ?>) cacheObj;
            int before = cache.size();
            cache.entrySet().removeIf(entry -> {
                Object key = entry.getKey();
                if (key instanceof Class<?>) {
                    return ((Class<?>) key).getClassLoader() == lingClassLoader;
                }
                if (key instanceof Member) {
                    return ((Member) key).getDeclaringClass().getClassLoader() == lingClassLoader;
                }
                return false;
            });
            int removed = before - cache.size();
            if (removed > 0) {
                log.info("[{}] LocalVariableTableParameterNameDiscoverer.parameterNamesCache: removed {} entries (was {} entries)",
                        lingId, removed, before);
            }
            return removed;
        } catch (Exception e) {
            log.debug("[{}] Failed to clear parameterNamesCache field: {}", lingId, e.getMessage());
            return 0;
        }
    }

    /** preCleanup / cleanup 阶段：清理 Property.annotationCache */
    void clearPropertyAnnotationCache(String lingId, ClassLoader lingClassLoader, String phase) {
        try {
            Class<?> propertyClass = Class.forName("org.springframework.core.convert.Property");
            Field cacheField = SpringCleanupSupport.findFieldInHierarchy(propertyClass, "annotationCache");
            if (cacheField == null) {
                return;
            }
            cacheField.setAccessible(true);
            Object cacheObj = cacheField.get(null);
            if (!(cacheObj instanceof Map<?, ?>)) {
                return;
            }
            Map<?, ?> cache = (Map<?, ?>) cacheObj;
            int before = cache.size();
            cache.entrySet()
                    .removeIf(entry -> SpringCleanupSupport.isRelatedToClassLoader(entry.getKey(), lingClassLoader)
                            || SpringCleanupSupport.isRelatedToClassLoader(entry.getValue(), lingClassLoader)
                            || isPropertyRelatedToClassLoader(entry.getKey(), lingClassLoader)
                            || isPropertyRelatedToClassLoader(entry.getValue(), lingClassLoader));
            int removed = before - cache.size();
            log.debug("[{}] Property.annotationCache ({}): removed {} entries", lingId, phase, removed);
        } catch (ClassNotFoundException e) {
            // 忽略该版本下不存在目标类的情况
        } catch (Exception e) {
            log.debug("[{}] Property.annotationCache cleanup failed ({}): {}", lingId, phase, e.getMessage());
        }
    }

    /** cleanup 阶段：清理 SpringFactoriesLoader 静态缓存 */
    void clearSpringFactoriesCache(String lingId, ClassLoader lingClassLoader) {
        try {
            int cleared = 0;
            for (Field field : SpringFactoriesLoader.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()))
                    continue;
                if (!Map.class.isAssignableFrom(field.getType()))
                    continue;
                try {
                    field.setAccessible(true);
                    Map<?, ?> map = (Map<?, ?>) field.get(null);
                    if (map != null) {
                        Object removed = map.remove(lingClassLoader);
                        if (removed != null) {
                            cleared++;
                            log.debug("[{}] Cleared SpringFactoriesLoader.{}", lingId, field.getName());
                        }
                    }
                } catch (Exception e) {
                    log.trace("[{}] Failed to clear field {}: {}", lingId, field.getName(), e.getMessage());
                }
            }
            if (cleared > 0) {
                log.info("[{}] Cleared {} SpringFactoriesLoader cache entries", lingId, cleared);
            }
        } catch (Exception e) {
            log.debug("[{}] SpringFactoriesLoader cleanup failed: {}", lingId, e.getMessage());
        }
    }

    // ======================== ClassUtils.cache 专用清理 ========================

    /**
     * 清理以 ClassLoader 为 key 的静态 Map 字段（如 ClassUtils.cache）。
     * <p>
     * 与 {@link SpringCleanupSupport#removeStaticMapEntries} 不同，本方法按
     * ClassLoader 精确匹配 key，避免误删非灵元条目。
     */
    private void clearStaticMapByClassLoaderKey(String lingId, ClassLoader lingClassLoader,
            String className, String fieldName) {
        try {
            Class<?> clazz = Class.forName(className);
            Field f = SpringCleanupSupport.findFieldInHierarchy(clazz, fieldName);
            if (f == null) {
                return;
            }
            f.setAccessible(true);
            Object cacheObj = f.get(null);
            if (!(cacheObj instanceof Map<?, ?>)) {
                return;
            }
            Map<?, ?> map = (Map<?, ?>) cacheObj;
            int removed = SpringCleanupSupport.removeByClassLoaderKey(map, lingClassLoader);
            if (removed > 0) {
                log.debug("[{}] {}.{}: removed {} entries", lingId, className, fieldName, removed);
            }
        } catch (ClassNotFoundException ignored) {
            // 版本差异，类不存在
        } catch (Exception e) {
            log.debug("[{}] {}.{} cleanup failed: {}", lingId, className, fieldName, e.getMessage());
        }
    }

    // ======================== ReflectionUtils ========================

    private void clearReflectionUtilsSelective(String lingId, ClassLoader lingClassLoader) {
        String[] cacheFieldNames = { "declaredFieldsCache", "declaredMethodsCache" };
        for (String fieldName : cacheFieldNames) {
            try {
                Field f = ReflectionUtils.findField(ReflectionUtils.class, fieldName);
                if (f == null)
                    continue;
                f.setAccessible(true);
                Map<?, ?> cache = (Map<?, ?>) f.get(null);
                if (cache == null)
                    continue;
                int before = cache.size();
                cache.entrySet().removeIf(entry -> {
                    Object key = entry.getKey();
                    return key instanceof Class<?>
                            && ((Class<?>) key).getClassLoader() == lingClassLoader;
                });
                int removed = before - cache.size();
                if (removed > 0) {
                    log.debug("[{}] ReflectionUtils.{}: removed {} entries", lingId, fieldName, removed);
                }
            } catch (Exception e) {
                log.trace("[{}] Failed to selectively clear ReflectionUtils.{}", lingId, fieldName);
            }
        }
    }

    // ======================== AnnotationUtils ========================

    private void clearAnnotationUtilsSelective(String lingId, ClassLoader lingClassLoader) {
        try {
            int totalRemoved = 0;
            for (Field f : AnnotationUtils.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()))
                    continue;
                if (!Map.class.isAssignableFrom(f.getType()))
                    continue;
                try {
                    f.setAccessible(true);
                    Map<?, ?> cache = (Map<?, ?>) f.get(null);
                    if (cache == null || cache.isEmpty())
                        continue;
                    int before = cache.size();
                    cache.entrySet().removeIf(
                            entry -> SpringCleanupSupport.isRelatedToClassLoader(entry.getKey(), lingClassLoader));
                    totalRemoved += (before - cache.size());
                } catch (Exception ignored) {
                }
            }
            // AnnotatedElementUtils
            try {
                Class<?> aeClass = Class.forName("org.springframework.core.annotation.AnnotatedElementUtils");
                for (Field f : aeClass.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()))
                        continue;
                    if (!Map.class.isAssignableFrom(f.getType()))
                        continue;
                    try {
                        f.setAccessible(true);
                        Map<?, ?> cache = (Map<?, ?>) f.get(null);
                        if (cache == null)
                            continue;
                        int before = cache.size();
                        cache.entrySet().removeIf(
                                entry -> SpringCleanupSupport.isRelatedToClassLoader(entry.getKey(), lingClassLoader));
                        totalRemoved += (before - cache.size());
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            // MergedAnnotations 相关（Spring 5.2+）
            try {
                Class<?> maClass = Class.forName("org.springframework.core.annotation.MergedAnnotationsCollection");
                SpringCleanupSupport.removeStaticMapEntries(maClass, lingClassLoader);
            } catch (Exception ignored) {
            }
            try {
                Class<?> maClass = Class.forName("org.springframework.core.annotation.TypeMappedAnnotations");
                totalRemoved += SpringCleanupSupport.removeStaticMapEntries(maClass, lingClassLoader);
            } catch (Exception ignored) {
            }
            try {
                Class<?> maClass = Class.forName("org.springframework.core.annotation.AnnotationTypeMappings");
                totalRemoved += SpringCleanupSupport.removeStaticMapEntries(maClass, lingClassLoader);
            } catch (Exception ignored) {
            }
            if (totalRemoved > 0) {
                log.debug("[{}] AnnotationUtils selective cleanup: removed {} entries total", lingId, totalRemoved);
            }
        } catch (Exception e) {
            log.debug("[{}] AnnotationUtils selective cleanup failed: {}", lingId, e.getMessage());
        }
    }

    // ======================== Micrometer TimedAnnotations ========================

    private void clearMicrometerCaches(String lingId, ClassLoader lingClassLoader) {
        try {
            Class<?> timedAnnotationsClass = Class
                    .forName("org.springframework.boot.actuate.metrics.annotation.TimedAnnotations");
            Field cacheField = SpringCleanupSupport.findFieldInHierarchy(timedAnnotationsClass, "cache");
            if (cacheField != null) {
                cacheField.setAccessible(true);
                Object cacheObj = cacheField.get(null);
                if (cacheObj instanceof Map<?, ?>) {
                    Map<?, ?> cache = (Map<?, ?>) cacheObj;
                    int before = cache.size();
                    cache.keySet().removeIf(key -> {
                        if (key instanceof Class) {
                            return ((Class<?>) key).getClassLoader() == lingClassLoader;
                        }
                        if (key instanceof Method) {
                            return ((Method) key).getDeclaringClass()
                                    .getClassLoader() == lingClassLoader;
                        }
                        return false;
                    });
                    int removed = before - cache.size();
                    if (removed > 0) {
                        log.debug("[{}] TimedAnnotations.cache: removed {} entries", lingId, removed);
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            // Ignored
        } catch (Exception e) {
            log.debug("[{}] TimedAnnotations.cache cleanup failed: {}", lingId, e.getMessage());
        }
    }

    // ======================== ResolvableType ========================

    private void clearResolvableTypeSelective(String lingId, ClassLoader lingClassLoader) {
        try {
            Field cacheField = ReflectionUtils.findField(ResolvableType.class, "cache");
            if (cacheField == null) {
                for (Field f : ResolvableType.class.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())
                            && Map.class.isAssignableFrom(f.getType())) {
                        cacheField = f;
                        break;
                    }
                }
            }
            if (cacheField == null)
                return;
            cacheField.setAccessible(true);
            Map<?, ?> cache = (Map<?, ?>) cacheField.get(null);
            if (cache == null || cache.isEmpty())
                return;
            // 使用 clearMapRelatedToClassLoader：CRHM purge + MethodClassKey/Soft 深度关联
            int removed = SpringCleanupSupport.clearMapRelatedToClassLoader(cache, lingClassLoader);
            // 再补一层 ResolvableType 自身 resolve/type 字段判断
            int before = cache.size();
            try {
                cache.entrySet().removeIf(entry -> isResolvableTypeRelated(entry.getKey(), lingClassLoader)
                        || isResolvableTypeRelated(entry.getValue(), lingClassLoader));
            } catch (Exception ignored) {
            }
            removed += Math.max(0, before - cache.size());
            if (removed > 0) {
                log.debug("[{}] ResolvableType cache: removed {} entries", lingId, removed);
            }
        } catch (Exception e) {
            log.debug("[{}] ResolvableType selective cleanup failed: {}", lingId, e.getMessage());
        }
    }

    /** 检查 ResolvableType 是否关联目标 ClassLoader */
    private boolean isResolvableTypeRelated(Object obj, ClassLoader cl) {
        if (obj == null)
            return false;
        try {
            Method resolveMethod = obj.getClass().getMethod("resolve");
            Object resolved = resolveMethod.invoke(obj);
            if (resolved instanceof Class<?>) {
                return ((Class<?>) resolved).getClassLoader() == cl;
            }
        } catch (Exception ignored) {
        }
        try {
            Field typeField = SpringCleanupSupport.findFieldInHierarchy(obj.getClass(), "type");
            if (typeField != null) {
                typeField.setAccessible(true);
                Object type = typeField.get(obj);
                if (type instanceof Class<?>) {
                    return ((Class<?>) type).getClassLoader() == cl;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ======================== BeanAnnotationHelper ========================

    /**
     * 清理 BeanAnnotationHelper 的 scopedProxyCache 和 beanNameCache。
     * <p>
     * 两个缓存 key 都是 {@link Method}，Method 持有 declaringClass → ClassLoader，
     * 会阻止灵元 CL GC。灵元中带 {@code @Bean} 方法的配置类一旦被扫描就会进入这两个缓存。
     */
    private void clearBeanAnnotationHelper(String lingId, ClassLoader lingClassLoader) {
        try {
            Class<?> clazz = Class.forName("org.springframework.context.annotation.BeanAnnotationHelper");
            int removed = 0;
            for (String fieldName : new String[]{ "scopedProxyCache", "beanNameCache" }) {
                try {
                    Field f = SpringCleanupSupport.findFieldInHierarchy(clazz, fieldName);
                    if (f == null)
                        continue;
                    f.setAccessible(true);
                    Object cacheObj = f.get(null);
                    if (!(cacheObj instanceof Map<?, ?>))
                        continue;
                    Map<?, ?> cache = (Map<?, ?>) cacheObj;
                    int before = cache.size();
                    cache.entrySet().removeIf(
                            entry -> SpringCleanupSupport.isRelatedToClassLoader(entry.getKey(), lingClassLoader));
                    int delta = before - cache.size();
                    if (delta > 0) {
                        log.debug("[{}] BeanAnnotationHelper.{}: removed {} entries", lingId, fieldName, delta);
                    }
                    removed += delta;
                } catch (Exception ignored) {
                    // 版本差异，字段不存在或结构不同
                }
            }
            if (removed > 0) {
                log.debug("[{}] BeanAnnotationHelper: removed {} entries total", lingId, removed);
            }
        } catch (ClassNotFoundException e) {
            // Spring 版本不同，类可能不存在
        } catch (Exception e) {
            log.debug("[{}] BeanAnnotationHelper cleanup failed: {}", lingId, e.getMessage());
        }
    }

    // ======================== Property 专用判断器 ========================

    private boolean isPropertyRelatedToClassLoader(Object obj, ClassLoader cl) {
        if (obj == null || cl == null) {
            return false;
        }
        if (obj instanceof Class<?>) {
            return ((Class<?>) obj).getClassLoader() == cl;
        }
        if (!"org.springframework.core.convert.Property".equals(obj.getClass().getName())) {
            return false;
        }
        try {
            Field objectType = SpringCleanupSupport.findFieldInHierarchy(obj.getClass(), "objectType");
            if (objectType != null) {
                objectType.setAccessible(true);
                Object type = objectType.get(obj);
                if (type instanceof Class<?> && ((Class<?>) type).getClassLoader() == cl) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            Field readMethod = SpringCleanupSupport.findFieldInHierarchy(obj.getClass(), "readMethod");
            if (readMethod != null) {
                readMethod.setAccessible(true);
                Object method = readMethod.get(obj);
                if (method instanceof Method
                        && ((Method) method).getDeclaringClass().getClassLoader() == cl) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            Field writeMethod = SpringCleanupSupport.findFieldInHierarchy(obj.getClass(), "writeMethod");
            if (writeMethod != null) {
                writeMethod.setAccessible(true);
                Object method = writeMethod.get(obj);
                if (method instanceof Method
                        && ((Method) method).getDeclaringClass().getClassLoader() == cl) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
