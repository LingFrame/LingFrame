package com.lingframe.core.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InvocationTypeResolver} 的补充测试。
 * <p>
 * 该类为包级可见的工具类，测试通过同包访问其静态方法。
 * 重点覆盖 8 种基本类型映射、null/空数组、普通类加载、不存在的类。
 */
@DisplayName("InvocationTypeResolver 补充测试")
class InvocationTypeResolverSupplementTest {

    @Test
    @DisplayName("resolveTypes null 应返回空数组")
    void shouldReturnEmptyArrayForNull() throws ClassNotFoundException {
        Class<?>[] types = InvocationTypeResolver.resolveTypes(null, getClass().getClassLoader());
        assertNotNull(types);
        assertEquals(0, types.length);
    }

    @Test
    @DisplayName("resolveTypes 空数组应返回空数组")
    void shouldReturnEmptyArrayForEmptyArray() throws ClassNotFoundException {
        Class<?>[] types = InvocationTypeResolver.resolveTypes(new String[0], getClass().getClassLoader());
        assertNotNull(types);
        assertEquals(0, types.length);
    }

    @Test
    @DisplayName("loadClass 应正确映射全部 8 种基本类型")
    void shouldMapAllPrimitiveTypes() throws ClassNotFoundException {
        assertEquals(int.class, InvocationTypeResolver.loadClass("int", null));
        assertEquals(long.class, InvocationTypeResolver.loadClass("long", null));
        assertEquals(double.class, InvocationTypeResolver.loadClass("double", null));
        assertEquals(boolean.class, InvocationTypeResolver.loadClass("boolean", null));
        assertEquals(byte.class, InvocationTypeResolver.loadClass("byte", null));
        assertEquals(short.class, InvocationTypeResolver.loadClass("short", null));
        assertEquals(float.class, InvocationTypeResolver.loadClass("float", null));
        assertEquals(char.class, InvocationTypeResolver.loadClass("char", null));
    }

    @Test
    @DisplayName("resolveTypes 应解析基本类型与引用类型混合数组")
    void shouldResolveMixedPrimitiveAndReferenceTypes() throws ClassNotFoundException {
        ClassLoader cl = getClass().getClassLoader();
        Class<?>[] types = InvocationTypeResolver.resolveTypes(
                new String[]{"int", "java.lang.String", "boolean", "java.lang.Integer"},
                cl);
        assertEquals(4, types.length);
        assertEquals(int.class, types[0]);
        assertEquals(String.class, types[1]);
        assertEquals(boolean.class, types[2]);
        assertEquals(Integer.class, types[3]);
    }

    @Test
    @DisplayName("loadClass 应通过 Class.forName 加载引用类型")
    void shouldLoadReferenceType() throws ClassNotFoundException {
        Class<?> cls = InvocationTypeResolver.loadClass("java.util.List", getClass().getClassLoader());
        assertEquals(java.util.List.class, cls);
    }

    @Test
    @DisplayName("loadClass 不存在的类应抛 ClassNotFoundException")
    void shouldThrowForUnknownClass() {
        assertThrows(ClassNotFoundException.class,
                () -> InvocationTypeResolver.loadClass("com.lingframe.notexist.FakeClass", getClass().getClassLoader()));
    }

    @Test
    @DisplayName("resolveTypes 单个元素数组应返回长度为 1 的数组")
    void shouldResolveSingleElementArray() throws ClassNotFoundException {
        Class<?>[] types = InvocationTypeResolver.resolveTypes(
                new String[]{"java.lang.Object"}, getClass().getClassLoader());
        assertEquals(1, types.length);
        assertEquals(Object.class, types[0]);
    }

    @Test
    @DisplayName("loadClass 应使用 initialize=false 加载类（不触发静态块副作用）")
    void shouldLoadClassWithoutInitialization() throws ClassNotFoundException {
        // 通过加载一个标准类验证 Class.forName(name, false, loader) 路径
        Class<?> cls = InvocationTypeResolver.loadClass("java.lang.Runnable", getClass().getClassLoader());
        assertEquals(Runnable.class, cls);
    }
}
