package com.lingframe.infra.cache.proxy;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.PermissionDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CacheNamespaceSupport 单元测试。
 * <p>
 * 重点覆盖跨灵元 NamespacedKey 伪造防御——灵元 A 不能用灵元 B 的 lingId 构造 key 来读写他灵缓存。
 */
@DisplayName("CacheNamespaceSupport 测试")
class CacheNamespaceSupportTest {

    @AfterEach
    void tearDown() {
        LingCallContext.clear();
    }

    @Nested
    @DisplayName("命名空间包装")
    class NamespaceKeyTests {

        @Test
        @DisplayName("有灵元上下文时，rawKey 应被包装为 NamespacedKey")
        void shouldWrapRawKeyWhenContextPresent() {
            LingCallContext.setLingId("ling-a");

            Object result = CacheNamespaceSupport.namespaceKey("users", "user:1");

            assertInstanceOf(CacheNamespaceSupport.NamespacedKey.class, result);
            CacheNamespaceSupport.NamespacedKey nk = (CacheNamespaceSupport.NamespacedKey) result;
            assertEquals("ling-a", nk.getLingId());
            assertEquals("users", nk.getCacheName());
            assertEquals("user:1", nk.getRawKey());
        }

        @Test
        @DisplayName("无灵元上下文时，rawKey 应原样返回（灵核直通）")
        void shouldReturnRawKeyWhenNoContext() {
            Object rawKey = "user:1";

            Object result = CacheNamespaceSupport.namespaceKey("users", rawKey);

            assertSame(rawKey, result);
        }

        @Test
        @DisplayName("rawKey 为 null 时应原样返回")
        void shouldReturnNullWhenRawKeyIsNull() {
            LingCallContext.setLingId("ling-a");

            Object result = CacheNamespaceSupport.namespaceKey("users", null);

            assertSame(null, result);
        }

        @Test
        @DisplayName("批量包装应保持顺序和数量")
        void shouldNamespaceMultipleKeys() {
            LingCallContext.setLingId("ling-a");

            List<Object> result = CacheNamespaceSupport.namespaceKeys("users", Arrays.asList("k1", "k2", "k3"));

            assertEquals(3, result.size());
            for (Object key : result) {
                assertInstanceOf(CacheNamespaceSupport.NamespacedKey.class, key);
                assertEquals("ling-a", CacheNamespaceSupport.extractLingId(key));
            }
        }

        @Test
        @DisplayName("空 Iterable 应返回空列表")
        void shouldReturnEmptyListForEmptyIterable() {
            List<Object> result = CacheNamespaceSupport.namespaceKeys("users", Collections.emptyList());

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("null Iterable 应返回空列表")
        void shouldReturnEmptyListForNullIterable() {
            List<Object> result = CacheNamespaceSupport.namespaceKeys("users", null);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("跨灵元伪造防御")
    class CrossLingForgeryDefenseTests {

        @Test
        @DisplayName("灵元 A 传入灵元 B 的 NamespacedKey 时应抛 PermissionDeniedException")
        void shouldRejectCrossLingNamespacedKey() {
            LingCallContext.setLingId("ling-a");
            CacheNamespaceSupport.NamespacedKey foreignKey =
                    new CacheNamespaceSupport.NamespacedKey("ling-b", "users", "user:1");

            PermissionDeniedException ex = assertThrows(PermissionDeniedException.class,
                    () -> CacheNamespaceSupport.namespaceKey("users", foreignKey));

            assertTrue(ex.getMessage().contains("Cross-ling namespace key detected"));
            assertTrue(ex.getMessage().contains("ling-a"));
            assertTrue(ex.getMessage().contains("ling-b"));
        }

        @Test
        @DisplayName("灵元传入自己的 NamespacedKey 时应原样返回（幂等）")
        void shouldAllowOwnNamespacedKey() {
            LingCallContext.setLingId("ling-a");
            CacheNamespaceSupport.NamespacedKey ownKey =
                    new CacheNamespaceSupport.NamespacedKey("ling-a", "users", "user:1");

            Object result = CacheNamespaceSupport.namespaceKey("users", ownKey);

            assertSame(ownKey, result);
        }

        @Test
        @DisplayName("批量包装中混入跨灵元 key 时应抛 PermissionDeniedException")
        void shouldRejectCrossLingKeyInBatch() {
            LingCallContext.setLingId("ling-a");
            CacheNamespaceSupport.NamespacedKey foreignKey =
                    new CacheNamespaceSupport.NamespacedKey("ling-b", "users", "user:1");

            assertThrows(PermissionDeniedException.class,
                    () -> CacheNamespaceSupport.namespaceKeys("users", Arrays.asList("k1", foreignKey, "k3")));
        }
    }

    @Nested
    @DisplayName("去命名空间还原")
    class DenamespaceTests {

        @Test
        @DisplayName("denamespaceKey 应从 NamespacedKey 还原原始 key")
        void shouldDenamespaceNamespacedKey() {
            CacheNamespaceSupport.NamespacedKey nk =
                    new CacheNamespaceSupport.NamespacedKey("ling-a", "users", "user:1");

            assertEquals("user:1", CacheNamespaceSupport.denamespaceKey(nk));
        }

        @Test
        @DisplayName("denamespaceKey 对非 NamespacedKey 应原样返回")
        void shouldReturnOriginalKeyForNonNamespacedKey() {
            assertEquals("plain-key", CacheNamespaceSupport.denamespaceKey("plain-key"));
        }

        @Test
        @DisplayName("denamespaceMapKeys 应还原 Map 中所有 key")
        void shouldDenamespaceAllMapKeys() {
            Map<Object, String> source = new LinkedHashMap<>();
            source.put(new CacheNamespaceSupport.NamespacedKey("ling-a", "users", "k1"), "v1");
            source.put(new CacheNamespaceSupport.NamespacedKey("ling-a", "users", "k2"), "v2");
            source.put("plain-key", "v3");

            Map<String, String> result = CacheNamespaceSupport.denamespaceMapKeys(source);

            assertEquals(3, result.size());
            assertEquals("v1", result.get("k1"));
            assertEquals("v2", result.get("k2"));
            assertEquals("v3", result.get("plain-key"));
        }

        @Test
        @DisplayName("denamespaceMapKeys 对 null 应返回空 Map")
        void shouldReturnEmptyMapForNull() {
            Map<?, ?> result = CacheNamespaceSupport.denamespaceMapKeys(null);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("工具方法")
    class UtilityMethodTests {

        @Test
        @DisplayName("isNamespacedKey 应正确识别 NamespacedKey")
        void shouldIdentifyNamespacedKey() {
            CacheNamespaceSupport.NamespacedKey nk =
                    new CacheNamespaceSupport.NamespacedKey("ling-a", "users", "k1");

            assertTrue(CacheNamespaceSupport.isNamespacedKey(nk));
            assertTrue(!CacheNamespaceSupport.isNamespacedKey("plain-key"));
        }

        @Test
        @DisplayName("extractLingId 应提取 lingId，非 NamespacedKey 返回 null")
        void shouldExtractLingId() {
            CacheNamespaceSupport.NamespacedKey nk =
                    new CacheNamespaceSupport.NamespacedKey("ling-a", "users", "k1");

            assertEquals("ling-a", CacheNamespaceSupport.extractLingId(nk));
            assertEquals(null, CacheNamespaceSupport.extractLingId("plain-key"));
        }

        @Test
        @DisplayName("extractCacheName 应提取 cacheName，非 NamespacedKey 返回 null")
        void shouldExtractCacheName() {
            CacheNamespaceSupport.NamespacedKey nk =
                    new CacheNamespaceSupport.NamespacedKey("ling-a", "users", "k1");

            assertEquals("users", CacheNamespaceSupport.extractCacheName(nk));
            assertEquals(null, CacheNamespaceSupport.extractCacheName("plain-key"));
        }

        @Test
        @DisplayName("NamespacedKey 的 equals/hashCode 应基于三字段")
        void namespacedKeyEqualsHashCodeShouldBeFieldBased() {
            CacheNamespaceSupport.NamespacedKey nk1 =
                    new CacheNamespaceSupport.NamespacedKey("ling-a", "users", "k1");
            CacheNamespaceSupport.NamespacedKey nk2 =
                    new CacheNamespaceSupport.NamespacedKey("ling-a", "users", "k1");
            CacheNamespaceSupport.NamespacedKey nk3 =
                    new CacheNamespaceSupport.NamespacedKey("ling-a", "users", "k2");

            assertEquals(nk1, nk2);
            assertEquals(nk1.hashCode(), nk2.hashCode());
            assertTrue(!nk1.equals(nk3));
        }

        @Test
        @DisplayName("NamespacedKey 的 toString 应包含 lingId:cacheName:rawKey")
        void namespacedKeyToStringShouldContainAllFields() {
            CacheNamespaceSupport.NamespacedKey nk =
                    new CacheNamespaceSupport.NamespacedKey("ling-a", "users", "user:1");

            assertEquals("ling-a:users:user:1", nk.toString());
        }
    }
}
