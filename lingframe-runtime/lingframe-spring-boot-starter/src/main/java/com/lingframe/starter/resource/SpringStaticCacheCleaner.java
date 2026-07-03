package com.lingframe.starter.resource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.CachedIntrospectionResults;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.util.ReflectionUtils;

import java.beans.Introspector;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * 清理 Spring 框架公开 API 的静态缓存。
 * <p>
 * 这些缓存 key 是灵元加载的 Class，会阻止灵元 ClassLoader GC。
 * 包括：CachedIntrospectionResults / ReflectionUtils / AnnotationUtils /
 * SpringFactoriesLoader / ResolvableType / Property.annotationCache /
 * ResourceBundle /
 * Micrometer TimedAnnotations。
 * <p>
 * 专用判断器 {@link #isPropertyRelatedToClassLoader} 和
 * {@link #isResolvableTypeRelated} 留在本类内部，不下沉到 Support。
 */
@Slf4j
final class SpringStaticCacheCleaner {

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
            int before = cache.size();
            cache.entrySet().removeIf(entry -> isResolvableTypeRelated(entry.getKey(), lingClassLoader)
                    || isResolvableTypeRelated(entry.getValue(), lingClassLoader));
            int removed = before - cache.size();
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
