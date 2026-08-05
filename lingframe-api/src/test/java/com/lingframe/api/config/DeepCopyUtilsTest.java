package com.lingframe.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DeepCopyUtils 深拷贝工具测试")
class DeepCopyUtilsTest {

    @Nested
    @DisplayName("deepCopyMap 边界场景")
    class BoundaryTests {

        @Test
        @DisplayName("null 入参应返回 null")
        void shouldReturnNullForNullInput() {
            assertNull(DeepCopyUtils.deepCopyMap(null));
        }

        @Test
        @DisplayName("空 Map 应返回新的空 Map（非同一引用）")
        void shouldReturnNewEmptyMapForEmptyInput() {
            Map<String, Object> source = new HashMap<>();
            Map<String, Object> copy = DeepCopyUtils.deepCopyMap(source);
            assertNotNull(copy);
            assertTrue(copy.isEmpty());
            assertNotSame(source, copy);
        }
    }

    @Nested
    @DisplayName("不可变值保留引用")
    class ImmutableValueTests {

        @Test
        @DisplayName("String / Number / Boolean 应保留原引用（不可变）")
        void shouldRetainReferenceForImmutableValues() {
            String strVal = "hello";
            Integer intVal = 42;
            Boolean boolVal = true;

            Map<String, Object> source = new HashMap<>();
            source.put("str", strVal);
            source.put("num", intVal);
            source.put("bool", boolVal);

            Map<String, Object> copy = DeepCopyUtils.deepCopyMap(source);

            assertSame(strVal, copy.get("str"));
            assertSame(intVal, copy.get("num"));
            assertSame(boolVal, copy.get("bool"));
        }

        @Test
        @DisplayName("null 值应保留 null")
        void shouldPreserveNullValue() {
            Map<String, Object> source = new HashMap<>();
            source.put("key", null);

            Map<String, Object> copy = DeepCopyUtils.deepCopyMap(source);
            assertNull(copy.get("key"));
            assertEquals(1, copy.size());
        }
    }

    @Nested
    @DisplayName("嵌套集合递归拷贝")
    class NestedCollectionTests {

        @Test
        @DisplayName("嵌套 Map 应递归拷贝为新的 Map")
        void shouldDeepCopyNestedMap() {
            Map<String, Object> inner = new HashMap<>();
            inner.put("key", "value");

            Map<String, Object> source = new HashMap<>();
            source.put("nested", inner);

            Map<String, Object> copy = DeepCopyUtils.deepCopyMap(source);

            Object copiedNested = copy.get("nested");
            assertTrue(copiedNested instanceof Map);
            assertNotSame(inner, copiedNested);
            assertEquals("value", ((Map<?, ?>) copiedNested).get("key"));
        }

        @Test
        @DisplayName("List 值应递归拷贝为新的 List")
        void shouldDeepCopyListValue() {
            List<Object> list = new ArrayList<>();
            list.add("a");
            list.add("b");

            Map<String, Object> source = new HashMap<>();
            source.put("list", list);

            Map<String, Object> copy = DeepCopyUtils.deepCopyMap(source);

            Object copiedList = copy.get("list");
            assertTrue(copiedList instanceof List);
            assertNotSame(list, copiedList);
            assertEquals(2, ((List<?>) copiedList).size());
            assertEquals("a", ((List<?>) copiedList).get(0));
        }

        @Test
        @DisplayName("Set 值应递归拷贝为新的 Set")
        void shouldDeepCopySetValue() {
            Set<Object> set = new LinkedHashSet<>();
            set.add("x");
            set.add("y");

            Map<String, Object> source = new HashMap<>();
            source.put("set", set);

            Map<String, Object> copy = DeepCopyUtils.deepCopyMap(source);

            Object copiedSet = copy.get("set");
            assertTrue(copiedSet instanceof Set);
            assertNotSame(set, copiedSet);
            assertEquals(2, ((Set<?>) copiedSet).size());
        }

        @Test
        @DisplayName("混合嵌套 Map/List/Set 应全部递归拷贝")
        void shouldDeepCopyMixedNestedCollections() {
            List<Object> innerList = new ArrayList<>();
            innerList.add("item");

            Map<String, Object> innerMap = new HashMap<>();
            innerMap.put("deep", innerList);

            Set<Object> innerSet = new LinkedHashSet<>();
            innerSet.add(innerMap);

            Map<String, Object> source = new HashMap<>();
            source.put("mixed", innerSet);

            Map<String, Object> copy = DeepCopyUtils.deepCopyMap(source);

            Object copiedSet = copy.get("mixed");
            assertTrue(copiedSet instanceof Set);
            Object setElement = ((Set<?>) copiedSet).iterator().next();
            assertTrue(setElement instanceof Map);
            assertNotSame(innerMap, setElement);
            Object deepValue = ((Map<?, ?>) setElement).get("deep");
            assertTrue(deepValue instanceof List);
            assertNotSame(innerList, deepValue);
            assertEquals("item", ((List<?>) deepValue).get(0));
        }

        @Test
        @DisplayName("嵌套 Map 的非 String key 应通过 String.valueOf 转换")
        void shouldConvertNestedMapKeyViaStringValueOf() {
            // deepCopyValue 对嵌套 Map 的 key 用 String.valueOf 转换
            Map<Number, Object> inner = new HashMap<>();
            inner.put(42, "value");

            Map<String, Object> source = new HashMap<>();
            source.put("nested", inner);

            Map<String, Object> copy = DeepCopyUtils.deepCopyMap(source);

            Object copiedNested = copy.get("nested");
            assertTrue(copiedNested instanceof Map);
            // key 应被转为 String "42"
            assertEquals("value", ((Map<?, ?>) copiedNested).get("42"));
        }
    }

    @Nested
    @DisplayName("深拷贝隔离性")
    class IsolationTests {

        @Test
        @DisplayName("修改副本的扁平值不应影响原 Map")
        void shouldNotAffectOriginalOnFlatModification() {
            Map<String, Object> source = new HashMap<>();
            source.put("key", "original");

            Map<String, Object> copy = DeepCopyUtils.deepCopyMap(source);
            copy.put("key", "modified");

            assertEquals("original", source.get("key"));
            assertEquals("modified", copy.get("key"));
        }

        @Test
        @DisplayName("修改副本的嵌套 Map 不应影响原 Map")
        void shouldNotAffectOriginalOnNestedMapModification() {
            Map<String, Object> inner = new HashMap<>();
            inner.put("key", "original");

            Map<String, Object> source = new HashMap<>();
            source.put("nested", inner);

            Map<String, Object> copy = DeepCopyUtils.deepCopyMap(source);
            @SuppressWarnings("unchecked")
            Map<String, Object> copiedInner = (Map<String, Object>) copy.get("nested");
            copiedInner.put("key", "modified");

            assertEquals("original", inner.get("key"), "原嵌套 Map 不应被修改");
            assertEquals("modified", copiedInner.get("key"));
        }

        @Test
        @DisplayName("修改副本的嵌套 List 不应影响原 Map")
        void shouldNotAffectOriginalOnNestedListModification() {
            List<Object> innerList = new ArrayList<>();
            innerList.add("original");

            Map<String, Object> source = new HashMap<>();
            source.put("list", innerList);

            Map<String, Object> copy = DeepCopyUtils.deepCopyMap(source);
            @SuppressWarnings("unchecked")
            List<Object> copiedList = (List<Object>) copy.get("list");
            copiedList.add("new-item");
            copiedList.set(0, "modified");

            assertEquals(1, innerList.size(), "原 List 不应被修改");
            assertEquals("original", innerList.get(0));
            assertEquals(2, copiedList.size());
            assertEquals("modified", copiedList.get(0));
        }

        @Test
        @DisplayName("修改副本的嵌套 Set 不应影响原 Map")
        void shouldNotAffectOriginalOnNestedSetModification() {
            Set<Object> innerSet = new LinkedHashSet<>();
            innerSet.add("original");

            Map<String, Object> source = new HashMap<>();
            source.put("set", innerSet);

            Map<String, Object> copy = DeepCopyUtils.deepCopyMap(source);
            @SuppressWarnings("unchecked")
            Set<Object> copiedSet = (Set<Object>) copy.get("set");
            copiedSet.add("new-item");

            assertEquals(1, innerSet.size(), "原 Set 不应被修改");
            assertTrue(innerSet.contains("original"));
            assertEquals(2, copiedSet.size());
        }
    }
}
