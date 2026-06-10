package com.lingframe.starter.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.support.DefaultConversionService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class JacksonCacheEvictUtil {

    private static final Logger log = LoggerFactory.getLogger(JacksonCacheEvictUtil.class);

    public static void evictByClassLoader(ObjectMapper objectMapper, ClassLoader targetLoader) {
        if (objectMapper == null) return;

        log.info("开始彻底清理 Jackson 缓存以释放 ClassLoader: {}", targetLoader);

        // 1. 清理 Serializer 缓存 (Map<TypeKey, JsonSerializer>)
        flushSerializerCache(objectMapper);

        // 2. 清理 Deserializer 缓存 (Map<JavaType, JsonDeserializer>)
        flushDeserializerCache(objectMapper);

        // 3. 清理 TypeFactory 缓存 (Map<AsKey, JavaType>)
        flushTypeFactoryCache(objectMapper);

        // 4. 清理 Spring 转换服务缓存
        clearConversionServiceCache();
    }

    private static void flushSerializerCache(ObjectMapper objectMapper) {
        try {
            Object sp = getFieldValue(objectMapper, "_serializerProvider");
            Object cache = getFieldValue(sp, "_serializerCache");

            // 直接清空 _sharedMap
            clearMapField(cache, "_sharedMap");

            // 必须置空只读快照，否则引用依然在快照里
            Object roMap = getFieldValue(cache, "_readOnlyMap");
            if (roMap instanceof AtomicReference) {
                ((AtomicReference<?>) roMap).set(null);
            }
            log.info("✅ SerializerCache 已彻底清空");
        } catch (Exception e) {
            log.warn("清理 SerializerCache 失败: {}", e.getMessage());
        }
    }

    private static void flushDeserializerCache(ObjectMapper objectMapper) {
        try {
            Object ctx = getFieldValue(objectMapper, "_deserializationContext");
            Object cache = getFieldValue(ctx, "_cache");

            // 清空 _cachedDeserializers (LRUMap)
            Object mapLike = getFieldValue(cache, "_cachedDeserializers");
            invokeClear(mapLike);

            // 置空只读快照
            Object roMap = getFieldValue(cache, "_readOnlyMap");
            if (roMap instanceof AtomicReference) {
                ((AtomicReference<?>) roMap).set(null);
            }
            log.info("✅ DeserializerCache 已彻底清空");
        } catch (Exception e) {
            log.warn("清理 DeserializerCache 失败: {}", e.getMessage());
        }
    }

    private static void flushTypeFactoryCache(ObjectMapper objectMapper) {
        try {
            Object tf = getFieldValue(objectMapper, "_typeFactory");
            Object cache = getFieldValue(tf, "_typeCache");

            // 清空 LRUMap
            invokeClear(cache);
            log.info("✅ TypeFactoryCache 已彻底清空");
        } catch (Exception e) {
            log.warn("清理 TypeFactoryCache 失败: {}", e.getMessage());
        }
    }

    private static void clearConversionServiceCache() {
        try {
            Object shared = DefaultConversionService.getSharedInstance();
            Object cache = getFieldValue(shared, "converterCache");
            invokeClear(cache);
            log.info("✅ ConversionServiceCache 已清空");
        } catch (Exception e) {
            log.warn("清理 ConversionServiceCache 失败: {}", e.getMessage());
        }
    }

    // ================= 反射辅助方法 =================

    private static void clearMapField(Object obj, String fieldName) {
        try {
            Object map = getFieldValue(obj, fieldName);
            if (map instanceof Map) {
                ((Map<?, ?>) map).clear();
            }
        } catch (Exception e) {
            log.trace("Failed to clear map field '{}': {}", fieldName, e.getMessage());
        }
    }

    private static void invokeClear(Object obj) {
        if (obj == null) return;
        try {
            // 如果是 Map
            if (obj instanceof Map) {
                ((Map<?, ?>) obj).clear();
                return;
            }
            // 如果是 Jackson 的 LRUMap (内部有 _map 字段)
            try {
                Object innerMap = getFieldValue(obj, "_map");
                if (innerMap instanceof Map) {
                    ((Map<?, ?>) innerMap).clear();
                }
            } catch (Exception e) {
                log.trace("LRUMap _map field not accessible: {}", e.getMessage());
            }

            // 尝试直接调用 clear 方法
            Method clear = obj.getClass().getMethod("clear");
            clear.setAccessible(true);
            clear.invoke(obj);
        } catch (Exception e) {
            log.trace("Failed to invoke clear on {}: {}", obj.getClass().getName(), e.getMessage());
        }
    }

    private static Object getFieldValue(Object obj, String fieldName) throws Exception {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(obj);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }
}