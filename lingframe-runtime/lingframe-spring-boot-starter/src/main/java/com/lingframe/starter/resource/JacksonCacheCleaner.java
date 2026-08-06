package com.lingframe.starter.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 清理灵核共享 ObjectMapper 的 Jackson 序列化缓存。
 * <p>
 * TypeFactory / DeserializerCache / SerializerCache 的 key 是灵元加载的 Class
 * 或持有灵元 Class 的 JavaType，会阻止灵元 ClassLoader GC。
 * <p>
 * 专用判断器 {@link #isJacksonRelatedToClassLoader} 留在本类内部。
 */
@Slf4j
final class JacksonCacheCleaner {

    void clear(String lingId, ApplicationContext mainContext, ClassLoader lingClassLoader, String phase) {
        if (mainContext == null || lingClassLoader == null) {
            return;
        }
        try {
            Map<String, ObjectMapper> mapperBeans = mainContext.getBeansOfType(ObjectMapper.class);
            Set<ObjectMapper> mappers = new HashSet<>(mapperBeans.values());
            try {
                Map<String, MappingJackson2HttpMessageConverter> converters = mainContext
                        .getBeansOfType(MappingJackson2HttpMessageConverter.class);
                for (MappingJackson2HttpMessageConverter converter : converters.values()) {
                    if (converter != null && converter.getObjectMapper() != null) {
                        mappers.add(converter.getObjectMapper());
                    }
                }
            } catch (Exception ignored) {
            }
            int totalRemoved = 0;
            for (ObjectMapper mapper : mappers) {
                totalRemoved += clearObjectMapperCaches(mapper, lingClassLoader);
            }
            log.debug("[{}] Jackson cache cleanup ({}): removed {} entries", lingId, phase, totalRemoved);
        } catch (Exception e) {
            log.debug("[{}] Jackson cache cleanup failed ({}): {}", lingId, phase, e.getMessage());
        }
    }

    private int clearObjectMapperCaches(ObjectMapper mapper, ClassLoader lingClassLoader) {
        int removed = 0;
        try { removed += clearTypeFactoryCache(mapper.getTypeFactory(), lingClassLoader); } catch (Exception e) { log.trace("TypeFactory cache cleanup failed: {}", e.getMessage()); }
        try { removed += clearObjectMapperRootDeserializers(mapper, lingClassLoader); } catch (Exception e) { log.trace("RootDeserializers cleanup failed: {}", e.getMessage()); }
        try { removed += clearDeserializerCache(mapper, lingClassLoader); } catch (Exception e) { log.trace("DeserializerCache cleanup failed: {}", e.getMessage()); }
        try { removed += clearSerializerCache(mapper, lingClassLoader); } catch (Exception e) { log.trace("SerializerCache cleanup failed: {}", e.getMessage()); }
        return removed;
    }

    private int clearTypeFactoryCache(TypeFactory typeFactory, ClassLoader lingClassLoader) {
        if (typeFactory == null) return 0;
        Field cacheField = SpringCleanupSupport.findFieldInHierarchy(typeFactory.getClass(), "_typeCache");
        if (cacheField == null) {
            cacheField = SpringCleanupSupport.findFieldInHierarchy(typeFactory.getClass(), "typeCache");
        }
        if (cacheField == null) return 0;
        try {
            cacheField.setAccessible(true);
            Object cacheObj = cacheField.get(typeFactory);
            if (cacheObj instanceof Map<?, ?>) {
                return removeJacksonMapEntries((Map<?, ?>) cacheObj, lingClassLoader);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private int clearObjectMapperRootDeserializers(ObjectMapper mapper, ClassLoader lingClassLoader) {
        Field field = SpringCleanupSupport.findFieldInHierarchy(mapper.getClass(), "_rootDeserializers");
        if (field == null) return 0;
        try {
            field.setAccessible(true);
            Object cacheObj = field.get(mapper);
            if (cacheObj instanceof Map<?, ?>) {
                return removeJacksonMapEntries((Map<?, ?>) cacheObj, lingClassLoader);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private int clearDeserializerCache(ObjectMapper mapper, ClassLoader lingClassLoader) {
        Field ctxField = SpringCleanupSupport.findFieldInHierarchy(mapper.getClass(), "_deserializationContext");
        if (ctxField == null) return 0;
        try {
            ctxField.setAccessible(true);
            Object ctx = ctxField.get(mapper);
            if (ctx == null) return 0;
            Field cacheField = SpringCleanupSupport.findFieldInHierarchy(ctx.getClass(), "_cache");
            if (cacheField == null) {
                cacheField = SpringCleanupSupport.findFieldInHierarchy(ctx.getClass(), "cache");
            }
            if (cacheField == null) return 0;
            cacheField.setAccessible(true);
            Object cache = cacheField.get(ctx);
            if (cache == null) return 0;
            return clearDeserializerCacheInternal(cache, lingClassLoader);
        } catch (Exception ignored) {}
        return 0;
    }

    private int clearDeserializerCacheInternal(Object cache, ClassLoader lingClassLoader) {
        int removed = 0;
        for (String fieldName : new String[] { "_cachedDeserializers", "_incompleteDeserializers" }) {
            Field f = SpringCleanupSupport.findFieldInHierarchy(cache.getClass(), fieldName);
            if (f == null) continue;
            try {
                f.setAccessible(true);
                Object mapObj = f.get(cache);
                if (mapObj instanceof Map<?, ?>) {
                    removed += removeJacksonMapEntries((Map<?, ?>) mapObj, lingClassLoader);
                }
            } catch (Exception ignored) {}
        }
        return removed;
    }

    private int clearSerializerCache(ObjectMapper mapper, ClassLoader lingClassLoader) {
        Field providerField = SpringCleanupSupport.findFieldInHierarchy(mapper.getClass(), "_serializerProvider");
        if (providerField == null) return 0;
        try {
            providerField.setAccessible(true);
            Object provider = providerField.get(mapper);
            if (provider == null) return 0;
            Field cacheField = SpringCleanupSupport.findFieldInHierarchy(provider.getClass(), "_serializerCache");
            if (cacheField == null) return 0;
            cacheField.setAccessible(true);
            Object cache = cacheField.get(provider);
            if (cache == null) return 0;
            return clearSerializerCacheInternal(cache, lingClassLoader);
        } catch (Exception ignored) {}
        return 0;
    }

    private int clearSerializerCacheInternal(Object cache, ClassLoader lingClassLoader) {
        int removed = 0;
        for (String fieldName : new String[] { "_sharedMap", "_readOnlyMap" }) {
            Field f = SpringCleanupSupport.findFieldInHierarchy(cache.getClass(), fieldName);
            if (f == null) continue;
            try {
                f.setAccessible(true);
                Object mapObj = f.get(cache);
                if (mapObj instanceof Map<?, ?>) {
                    removed += removeJacksonMapEntries((Map<?, ?>) mapObj, lingClassLoader);
                }
            } catch (Exception ignored) {}
        }
        return removed;
    }

    private int removeJacksonMapEntries(Map<?, ?> map, ClassLoader lingClassLoader) {
        int before = map.size();
        try {
            map.entrySet().removeIf(entry -> isJacksonRelatedToClassLoader(entry.getKey(), lingClassLoader)
                    || isJacksonRelatedToClassLoader(entry.getValue(), lingClassLoader)
                    || SpringCleanupSupport.isRelatedToClassLoader(entry.getKey(), lingClassLoader)
                    || SpringCleanupSupport.isRelatedToClassLoader(entry.getValue(), lingClassLoader));
        } catch (UnsupportedOperationException e) {
            try {
                Iterator<?> it = map.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<?, ?> entry = (Map.Entry<?, ?>) it.next();
                    if (isJacksonRelatedToClassLoader(entry.getKey(), lingClassLoader)
                            || isJacksonRelatedToClassLoader(entry.getValue(), lingClassLoader)
                            || SpringCleanupSupport.isRelatedToClassLoader(entry.getKey(), lingClassLoader)
                            || SpringCleanupSupport.isRelatedToClassLoader(entry.getValue(), lingClassLoader)) {
                        it.remove();
                    }
                }
            } catch (Exception ignored) {}
        }
        return before - map.size();
    }

    /** 检查 Jackson 相关对象是否关联目标 ClassLoader */
    private boolean isJacksonRelatedToClassLoader(Object obj, ClassLoader cl) {
        if (obj == null || cl == null) {
            return false;
        }
        if (obj instanceof Class<?>) {
            return ((Class<?>) obj).getClassLoader() == cl;
        }
        String cn = obj.getClass().getName();
        if (cn.equals("com.fasterxml.jackson.databind.JavaType")
                || cn.startsWith("com.fasterxml.jackson.databind.type.")) {
            try {
                Method m = obj.getClass().getMethod("getRawClass");
                Object raw = m.invoke(obj);
                if (raw instanceof Class<?>) {
                    return ((Class<?>) raw).getClassLoader() == cl;
                }
            } catch (Exception ignored) {}
            try {
                Field f = SpringCleanupSupport.findFieldInHierarchy(obj.getClass(), "_class");
                if (f == null) {
                    f = SpringCleanupSupport.findFieldInHierarchy(obj.getClass(), "_rawClass");
                }
                if (f != null) {
                    f.setAccessible(true);
                    Object raw = f.get(obj);
                    if (raw instanceof Class<?>) {
                        return ((Class<?>) raw).getClassLoader() == cl;
                    }
                }
            } catch (Exception ignored) {}
        }
        if (cn.contains("TypeKey")) {
            try {
                Field f = SpringCleanupSupport.findFieldInHierarchy(obj.getClass(), "_class");
                if (f != null) {
                    f.setAccessible(true);
                    Object raw = f.get(obj);
                    if (raw instanceof Class<?>) {
                        return ((Class<?>) raw).getClassLoader() == cl;
                    }
                }
                Field typeField = SpringCleanupSupport.findFieldInHierarchy(obj.getClass(), "_type");
                if (typeField != null) {
                    typeField.setAccessible(true);
                    Object type = typeField.get(obj);
                    if (isJacksonRelatedToClassLoader(type, cl)) {
                        return true;
                    }
                }
            } catch (Exception ignored) {}
        }
        if (cn.contains("ClassKey")) {
            try {
                Field f = SpringCleanupSupport.findFieldInHierarchy(obj.getClass(), "clazz");
                if (f == null) {
                    f = SpringCleanupSupport.findFieldInHierarchy(obj.getClass(), "_class");
                }
                if (f != null) {
                    f.setAccessible(true);
                    Object raw = f.get(obj);
                    if (raw instanceof Class<?>) {
                        return ((Class<?>) raw).getClassLoader() == cl;
                    }
                }
            } catch (Exception ignored) {}
        }
        return false;
    }
}
