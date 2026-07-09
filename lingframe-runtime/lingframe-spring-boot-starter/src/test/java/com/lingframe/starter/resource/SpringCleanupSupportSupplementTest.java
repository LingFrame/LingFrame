package com.lingframe.starter.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SpringCleanupSupport} 补充测试。
 * <p>
 * 该类为包级可见 final 工具类，仅含静态方法，测试置于同包下直接访问。
 * 重点覆盖反射查找、ClassLoader 关联判断、Map 条目精确移除等工具方法。
 */
@DisplayName("SpringCleanupSupport 补充测试")
class SpringCleanupSupportSupplementTest {

    @Test
    @DisplayName("findFieldInHierarchy 应能在继承链中找到字段并返回 null")
    void shouldFindAndNotFoundFieldInHierarchy() {
        // ChildClass 自身未声明 value，但其父类 ParentClass 声明了
        Field field = SpringCleanupSupport.findFieldInHierarchy(ChildClass.class, "value");
        assertNotNull(field);
        assertEquals("value", field.getName());

        // 不存在的字段应返回 null
        assertNull(SpringCleanupSupport.findFieldInHierarchy(ChildClass.class, "nonExistent"));
    }

    @Test
    @DisplayName("isRelatedToClassLoader 对 null / 不相关 / 关联对象应正确判断")
    void shouldJudgeClassLoaderRelation() {
        ClassLoader cl = getClass().getClassLoader();

        assertFalse(SpringCleanupSupport.isRelatedToClassLoader(null, cl));
        // String 实例的 ClassLoader 不会等于测试 CL
        assertFalse(SpringCleanupSupport.isRelatedToClassLoader("string", cl));
        // 测试类自身由测试 CL 加载
        assertTrue(SpringCleanupSupport.isRelatedToClassLoader(getClass(), cl));
    }

    @Test
    @DisplayName("isTargetClassLoader 应正确判断 ClassLoader 继承链")
    void shouldJudgeClassLoaderInheritance() {
        ClassLoader parent = ClassLoader.getSystemClassLoader();
        ClassLoader child = new ClassLoader(parent) {
        };

        // child 继承链上包含 parent
        assertTrue(SpringCleanupSupport.isTargetClassLoader(child, parent));
        // 自身匹配
        assertTrue(SpringCleanupSupport.isTargetClassLoader(child, child));
        // parent 继承链上不包含 child
        assertFalse(SpringCleanupSupport.isTargetClassLoader(parent, child));
        // null CL 直接返回 false
        assertFalse(SpringCleanupSupport.isTargetClassLoader(null, parent));
    }

    @Test
    @DisplayName("removeByClassLoaderKey 应移除目标 ClassLoader 加载的 Class 作为 key 的条目")
    void shouldRemoveEntriesByClassLoaderKey() {
        ClassLoader cl = getClass().getClassLoader();
        Map<Class<?>, String> map = new HashMap<>();
        // 测试类由 cl 加载，应被移除
        map.put(getClass(), "test");
        // String 由 bootstrap CL 加载（getClassLoader 返回 null），不应被移除
        map.put(String.class, "system");

        int removed = SpringCleanupSupport.removeByClassLoaderKey(map, cl);

        assertEquals(1, removed);
        assertFalse(map.containsKey(getClass()));
        assertTrue(map.containsKey(String.class));
    }

    @Test
    @DisplayName("findStaticFieldByType 应能找到指定类型的静态字段")
    void shouldFindStaticFieldByType() {
        // ClassWithStaticField 有 String 类型的静态字段 staticValue
        Field field = SpringCleanupSupport.findStaticFieldByType(ClassWithStaticField.class, "java.lang.String");
        assertNotNull(field);
        assertEquals("staticValue", field.getName());

        // 不匹配的类型应返回 null
        assertNull(SpringCleanupSupport.findStaticFieldByType(ClassWithStaticField.class, "java.lang.Integer"));
    }

    /** 用于测试 findFieldInHierarchy 的继承链查找 */
    static class ParentClass {
        private String value;
    }

    static class ChildClass extends ParentClass {
    }

    /** 用于测试 findStaticFieldByType */
    static class ClassWithStaticField {
        static String staticValue = "test";
    }
}
