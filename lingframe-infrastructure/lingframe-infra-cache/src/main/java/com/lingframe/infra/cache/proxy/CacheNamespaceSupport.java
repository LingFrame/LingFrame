package com.lingframe.infra.cache.proxy;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.PermissionDeniedException;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 本地缓存命名空间支持。
 * <p>
 * 目标不是改变调用方看到的 key 语义，而是在底层缓存中为不同灵元附加隔离边界，
 * 避免共享本地缓存时出现跨灵元读写串扰。
 */
public final class CacheNamespaceSupport {

    private CacheNamespaceSupport() {
    }

    public static Object namespaceKey(String cacheName, Object rawKey) {
        String lingId = LingCallContext.getLingId();
        if (lingId == null || rawKey == null) {
            return rawKey;
        }
        // 防御：若传入的 key 已是 NamespacedKey，校验 lingId 一致性，防止跨灵元伪造
        if (rawKey instanceof NamespacedKey) {
            NamespacedKey nk = (NamespacedKey) rawKey;
            if (!lingId.equals(nk.getLingId())) {
                throw new PermissionDeniedException(
                        "Cross-ling namespace key detected: current=" + lingId
                                + ", key.lingId=" + nk.getLingId());
            }
            return rawKey;
        }
        return new NamespacedKey(lingId, cacheName, rawKey);
    }

    public static List<Object> namespaceKeys(String cacheName, Iterable<?> rawKeys) {
        List<Object> namespacedKeys = new ArrayList<>();
        if (rawKeys == null) {
            return namespacedKeys;
        }
        for (Object rawKey : rawKeys) {
            namespacedKeys.add(namespaceKey(cacheName, rawKey));
        }
        return namespacedKeys;
    }

    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> denamespaceMapKeys(Map<?, V> source) {
        Map<K, V> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        for (Map.Entry<?, V> entry : source.entrySet()) {
            Object rawKey = denamespaceKey(entry.getKey());
            result.put((K) rawKey, entry.getValue());
        }
        return result;
    }

    public static Object denamespaceKey(Object namespacedKey) {
        return namespacedKey instanceof NamespacedKey
                ? ((NamespacedKey) namespacedKey).getRawKey()
                : namespacedKey;
    }

    /** 判断 key 是否为 NamespacedKey */
    public static boolean isNamespacedKey(Object key) {
        return key instanceof NamespacedKey;
    }

    /** 提取 NamespacedKey 的 lingId，非 NamespacedKey 返回 null */
    public static String extractLingId(Object key) {
        return key instanceof NamespacedKey ? ((NamespacedKey) key).getLingId() : null;
    }

    /** 提取 NamespacedKey 的 cacheName，非 NamespacedKey 返回 null */
    public static String extractCacheName(Object key) {
        return key instanceof NamespacedKey ? ((NamespacedKey) key).getCacheName() : null;
    }

    /**
     * 命名空间 key：包级私有，防止灵元外部构造伪造跨灵元 key。
     * <p>
     * 配合 {@link #namespaceKey} 入口的 lingId 一致性校验形成双重防御。
     */
    static final class NamespacedKey implements Serializable {
        private final String lingId;
        private final String cacheName;
        private final Object rawKey;

        NamespacedKey(String lingId, String cacheName, Object rawKey) {
            this.lingId = lingId;
            this.cacheName = cacheName;
            this.rawKey = rawKey;
        }

        String getLingId() {
            return lingId;
        }

        String getCacheName() {
            return cacheName;
        }

        Object getRawKey() {
            return rawKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof NamespacedKey)) {
                return false;
            }
            NamespacedKey that = (NamespacedKey) o;
            return Objects.equals(lingId, that.lingId)
                    && Objects.equals(cacheName, that.cacheName)
                    && Objects.equals(rawKey, that.rawKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(lingId, cacheName, rawKey);
        }

        @Override
        public String toString() {
            return lingId + ":" + cacheName + ":" + rawKey;
        }
    }
}
