package com.lingframe.dashboard.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ParameterParsingUtils 参数解析工具测试")
class ParameterParsingUtilsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("resolvePrimitiveType 原始类型解析")
    class ResolvePrimitiveTypeTests {

        @Test
        @DisplayName("8 种原始类型应正确解析")
        void shouldResolveAllPrimitiveTypes() {
            assertEquals(int.class, ParameterParsingUtils.resolvePrimitiveType("int"));
            assertEquals(long.class, ParameterParsingUtils.resolvePrimitiveType("long"));
            assertEquals(double.class, ParameterParsingUtils.resolvePrimitiveType("double"));
            assertEquals(float.class, ParameterParsingUtils.resolvePrimitiveType("float"));
            assertEquals(boolean.class, ParameterParsingUtils.resolvePrimitiveType("boolean"));
            assertEquals(char.class, ParameterParsingUtils.resolvePrimitiveType("char"));
            assertEquals(byte.class, ParameterParsingUtils.resolvePrimitiveType("byte"));
            assertEquals(short.class, ParameterParsingUtils.resolvePrimitiveType("short"));
        }

        @Test
        @DisplayName("非原始类型名应返回 null")
        void shouldReturnNullForNonPrimitiveType() {
            assertNull(ParameterParsingUtils.resolvePrimitiveType("java.lang.String"));
            assertNull(ParameterParsingUtils.resolvePrimitiveType("java.util.Map"));
            assertNull(ParameterParsingUtils.resolvePrimitiveType("unknown"));
        }
    }

    @Nested
    @DisplayName("resolveClass 类型解析")
    class ResolveClassTests {

        @Test
        @DisplayName("原始类型名应解析为原始 Class")
        void shouldResolvePrimitiveTypeViaResolveClass() throws Exception {
            assertEquals(int.class, ParameterParsingUtils.resolveClass("int", null));
            assertEquals(boolean.class, ParameterParsingUtils.resolveClass("boolean", null));
        }

        @Test
        @DisplayName("普通类名应通过 Class.forName 解析（无 ClassLoader）")
        void shouldResolveRegularClassWithoutClassLoader() throws Exception {
            assertEquals(String.class, ParameterParsingUtils.resolveClass("java.lang.String", null));
            assertEquals(Integer.class, ParameterParsingUtils.resolveClass("java.lang.Integer", null));
        }

        @Test
        @DisplayName("不存在的类应抛 ClassNotFoundException")
        void shouldThrowForNonExistentClass() {
            assertThrows(ClassNotFoundException.class,
                    () -> ParameterParsingUtils.resolveClass("com.nonexistent.FakeClass", null));
        }

        @Test
        @DisplayName("resolveClass 只加载不初始化（不触发静态块副作用）")
        void shouldLoadClassWithoutInitialization() throws Exception {
            String name = "com.lingframe.dashboard.util.ParameterParsingUtilsTest$StaticInitProbe";
            assertTrue(StaticInitRegistry.initializedClasses.isEmpty(), "前置：探针类尚未被初始化");

            Class<?> clazz = ParameterParsingUtils.resolveClass(name, null);
            assertEquals(name, clazz.getName());
            assertTrue(StaticInitRegistry.initializedClasses.isEmpty(),
                    "resolveClass 不应触发类初始化（静态块副作用被禁止）");

            // 对照：显式初始化后标记应出现，证明本测试具备检测类初始化的能力
            Class.forName(name, true, clazz.getClassLoader());
            assertTrue(StaticInitRegistry.initializedClasses.contains(name),
                    "对照：显式 Class.forName(init=true) 应触发静态块");
        }
    }

    @Nested
    @DisplayName("convertValue 类型转换")
    class ConvertValueTests {

        @Test
        @DisplayName("null 值应返回 null")
        void shouldReturnNullForNullValue() {
            assertNull(ParameterParsingUtils.convertValue(null, String.class, objectMapper));
        }

        @Test
        @DisplayName("值已是目标类型应直接返回同一实例")
        void shouldReturnSameInstanceIfAlreadyTargetType() {
            String value = "hello";
            assertSame(value, ParameterParsingUtils.convertValue(value, String.class, objectMapper));
        }

        @Test
        @DisplayName("Number 应转为 int（原始类型与包装类型均可）")
        void shouldConvertNumberToInt() {
            assertEquals(42, ParameterParsingUtils.convertValue(42L, int.class, objectMapper));
            assertEquals(42, ParameterParsingUtils.convertValue(42.0, Integer.class, objectMapper));
        }

        @Test
        @DisplayName("String 应转为 int")
        void shouldConvertStringToInt() {
            assertEquals(42, ParameterParsingUtils.convertValue("42", int.class, objectMapper));
        }

        @Test
        @DisplayName("String 应转为 long / double / float")
        void shouldConvertStringToLongDoubleFloat() {
            assertEquals(100L, ParameterParsingUtils.convertValue("100", long.class, objectMapper));
            assertEquals(3.14, ParameterParsingUtils.convertValue("3.14", double.class, objectMapper));
            assertEquals(1.5f, ParameterParsingUtils.convertValue("1.5", float.class, objectMapper));
        }

        @Test
        @DisplayName("String 应转为 boolean")
        void shouldConvertStringToBoolean() {
            assertTrue((Boolean) ParameterParsingUtils.convertValue("true", boolean.class, objectMapper));
            assertFalse((Boolean) ParameterParsingUtils.convertValue("false", Boolean.class, objectMapper));
        }

        @Test
        @DisplayName("非法数字字符串应抛 IllegalArgumentException")
        void shouldThrowForInvalidNumberString() {
            assertThrows(IllegalArgumentException.class,
                    () -> ParameterParsingUtils.convertValue("abc", int.class, objectMapper));
        }

        @Test
        @DisplayName("JSON 字符串应通过 readValue 反序列化为 POJO")
        void shouldConvertJsonStringToPojo() {
            String json = "{\"name\":\"test\",\"age\":30}";
            Object result = ParameterParsingUtils.convertValue(json, TestPojo.class, objectMapper);
            assertTrue(result instanceof TestPojo);
            assertEquals("test", ((TestPojo) result).name);
            assertEquals(30, ((TestPojo) result).age);
        }

        @Test
        @DisplayName("非 JSON 字符串转 POJO 应回退到 convertValue")
        void shouldFallbackToConvertValueForNonJsonString() {
            // "123" 不是 JSON 对象/数组，走 convertValue 路径转为 Integer
            Object result = ParameterParsingUtils.convertValue("123", Integer.class, objectMapper);
            assertEquals(123, result);
        }

        @Test
        @DisplayName("无法转换到目标类型应抛 IllegalArgumentException")
        void shouldThrowWhenCannotConvertToTargetClass() {
            // 无法将随机字符串转为 TestPojo
            assertThrows(IllegalArgumentException.class,
                    () -> ParameterParsingUtils.convertValue("not-json", TestPojo.class, objectMapper));
        }
    }

    @Nested
    @DisplayName("convertArgs 参数数组转换")
    class ConvertArgsTests {

        @Test
        @DisplayName("null parameterTypes 应直接返回 args")
        void shouldReturnArgsForNullParameterTypes() throws Exception {
            Object[] args = {"x"};
            Object[] result = ParameterParsingUtils.convertArgs(null, args, null, objectMapper);
            assertSame(args, result);
        }

        @Test
        @DisplayName("null args 应返回 null")
        void shouldReturnNullForNullArgs() throws Exception {
            assertNull(ParameterParsingUtils.convertArgs(new String[]{"int"}, null, null, objectMapper));
        }

        @Test
        @DisplayName("空 parameterTypes 应直接返回 args")
        void shouldReturnArgsForEmptyParameterTypes() throws Exception {
            Object[] args = {"x", 1};
            Object[] result = ParameterParsingUtils.convertArgs(new String[0], args, null, objectMapper);
            assertSame(args, result);
        }

        @Test
        @DisplayName("正常类型转换应正确处理每个参数")
        void shouldConvertAllArgsByType() throws Exception {
            String[] types = {"int", "java.lang.String", "boolean"};
            Object[] args = {"42", "hello", "true"};

            Object[] result = ParameterParsingUtils.convertArgs(types, args, null, objectMapper);

            assertEquals(3, result.length);
            assertEquals(42, result[0]);
            assertEquals("hello", result[1]);
            assertEquals(true, result[2]);
        }

        @Test
        @DisplayName("args 比 parameterTypes 多时多余参数应保留原值")
        void shouldKeepExtraArgsBeyondParameterTypes() throws Exception {
            String[] types = {"int"};
            Object[] args = {"42", "extra"};

            Object[] result = ParameterParsingUtils.convertArgs(types, args, null, objectMapper);

            assertEquals(2, result.length);
            assertEquals(42, result[0]);
            assertEquals("extra", result[1]);
        }

        @Test
        @DisplayName("value 为 null 的参数应保留 null")
        void shouldPreserveNullArgValue() throws Exception {
            String[] types = {"int", "java.lang.String"};
            Object[] args = {42, null};

            Object[] result = ParameterParsingUtils.convertArgs(types, args, null, objectMapper);

            assertEquals(42, result[0]);
            assertNull(result[1]);
        }

        @Test
        @DisplayName("转换失败应抛 IllegalArgumentException 并包含参数索引信息")
        void shouldThrowWithArgIndexOnConversionFailure() {
            String[] types = {"int", "int"};
            Object[] args = {"42", "not-a-number"};

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> ParameterParsingUtils.convertArgs(types, args, null, objectMapper));
            assertTrue(ex.getMessage().contains("arg[1]"), "异常信息应包含失败的参数索引");
        }
    }

    /** 测试用 POJO，用于验证 JSON 反序列化路径 */
    public static class TestPojo {
        public String name;
        public int age;
    }

    /** 记录已被初始化的类名（供「resolveClass 禁初始化」测试使用） */
    public static class StaticInitRegistry {
        public static final java.util.List<String> initializedClasses = new java.util.ArrayList<>();
    }

    /** 静态块含副作用的探针类：被初始化时应向 {@link StaticInitRegistry} 写入标记 */
    public static class StaticInitProbe {
        static {
            StaticInitRegistry.initializedClasses.add(StaticInitProbe.class.getName());
        }
    }
}
