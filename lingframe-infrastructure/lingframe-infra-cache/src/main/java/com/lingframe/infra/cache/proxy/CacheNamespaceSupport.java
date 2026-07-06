package com.lingframe.infra.cache.proxy;

import com.lingframe.api.context.LingCallContext;

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
        if (lingId == null || rawKey == null || rawKey instanceof NamespacedKey) {
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

    public static final class NamespacedKey implements Serializable {
        private final String lingId;
        private final String cacheName;
        private final Object rawKey;

        public NamespacedKey(String lingId, String cacheName, Object rawKey) {
            this.lingId = lingId;
            this.cacheName = cacheName;
            this.rawKey = rawKey;
        }

        public String getLingId() {
            return lingId;
        }

        public String getCacheName() {
            return cacheName;
        }

        public Object getRawKey() {
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
