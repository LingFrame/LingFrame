package com.lingframe.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * LingDefinition properties 原生递归深拷贝测试。
 */
@DisplayName("LingDefinition properties 深拷贝测试")
class LingDefinitionCopyTest {

    @Test
    @DisplayName("properties 含嵌套 Map/List/Set 时深拷贝生效，修改原对象不影响副本")
    void shouldDeepCopyNestedCollections() {
        LingDefinition original = new LingDefinition();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("string", "value");
        props.put("number", 42);

        List<Object> nestedList = new ArrayList<>();
        nestedList.add("a");
        Map<String, Object> nestedMap = new HashMap<>();
        nestedMap.put("key", "val");
        nestedList.add(nestedMap);
        props.put("list", nestedList);

        Set<Object> nestedSet = new LinkedHashSet<>();
        nestedSet.add("x");
        props.put("set", nestedSet);

        original.setProperties(props);

        LingDefinition copy = original.copy();
        Map<String, Object> copyProps = copy.getProperties();

        // 顶层 Map 引用不同
        assertNotSame(original.getProperties(), copyProps);
        // 嵌套 List 引用不同
        assertNotSame(nestedList, copyProps.get("list"));
        // 嵌套 Map 引用不同
        List<?> copyList = (List<?>) copyProps.get("list");
        assertNotSame(nestedMap, copyList.get(1));

        // 修改原对象不影响副本
        nestedList.add("polluted");
        assertEquals(2, copyList.size(), "副本不应受原对象修改影响");
    }

    @Test
    @DisplayName("String/Number 值保留引用（不可变）")
    void shouldKeepImmutableValueReferences() {
        LingDefinition original = new LingDefinition();
        Map<String, Object> props = new HashMap<>();
        String s = "immutable";
        props.put("s", s);
        original.setProperties(props);

        LingDefinition copy = original.copy();
        assertEquals(s, copy.getProperties().get("s"));
    }

    @Test
    @DisplayName("null properties 安全处理（不抛 NPE）")
    void shouldHandleNullProperties() {
        LingDefinition original = new LingDefinition();
        original.setProperties(null);

        assertDoesNotThrow(() -> {
            LingDefinition copy = original.copy();
            // copy 后 properties 可能为 null 或空 map，关键是深拷贝不抛异常
            if (copy.getProperties() != null) {
                copy.getProperties().clear();
            }
        });
    }
}
