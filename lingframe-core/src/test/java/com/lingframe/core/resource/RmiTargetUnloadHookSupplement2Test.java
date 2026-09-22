package com.lingframe.core.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link RmiTargetUnloadHook} 的第二轮补充测试。
 * <p>
 * 已有 {@link RmiTargetUnloadHookSupplementTest} 覆盖安全校验早返回路径，
 * 此处通过反射直接调用 private 方法覆盖核心反射逻辑：
 * {@code extractTargetClassLoader}（ccl 字段路径 / fallback 字段扫描路径）、
 * {@code extractClassLoaderViaFields}、{@code isLoadedBy}，
 * 以及 {@code clearObjectTable} 的真实注入清理路径。
 * <p>
 * 注入 ObjectTable 的路径需要
 * {@code --add-opens java.rmi/sun.rmi.transport=ALL-UNNAMED}；
 * 缺省时跳过注入断言（生产钩子本身会降级为 no-op 并打 debug 日志）。
 */
@DisplayName("RmiTargetUnloadHook 补充测试 II（核心反射逻辑）")
class RmiTargetUnloadHookSupplement2Test {

    private final RmiTargetUnloadHook hook = new RmiTargetUnloadHook();

    // ==================== 辅助类型 ====================

    /** 模拟 RMI Target（有 ccl 字段） */
    private static class FakeRmiTarget {
        @SuppressWarnings("unused")
        private final ClassLoader ccl;

        FakeRmiTarget(ClassLoader ccl) {
            this.ccl = ccl;
        }
    }

    /** 没有 ccl 字段，但有其他 ClassLoader 类型字段（触发 fallback 字段扫描） */
    private static class TargetWithOtherLoaderField {
        @SuppressWarnings("unused")
        private final ClassLoader loader;

        TargetWithOtherLoaderField(ClassLoader loader) {
            this.loader = loader;
        }
    }

    /** 没有任何 ClassLoader 类型字段 */
    private static class TargetWithoutLoader {
        @SuppressWarnings("unused")
        private final String name;

        TargetWithoutLoader(String name) {
            this.name = name;
        }
    }

    // ==================== extractTargetClassLoader ====================

    @Test
    @DisplayName("extractTargetClassLoader 应从 ccl 字段提取 ClassLoader")
    void shouldExtractClassLoaderFromCclField() throws Exception {
        ClassLoader customCL = new ClassLoader() {
        };
        FakeRmiTarget target = new FakeRmiTarget(customCL);

        Method m = RmiTargetUnloadHook.class.getDeclaredMethod(
                "extractTargetClassLoader", Object.class);
        m.setAccessible(true);
        ClassLoader result = (ClassLoader) m.invoke(hook, target);
        assertSame(customCL, result);
    }

    @Test
    @DisplayName("extractTargetClassLoader 在无 ccl 字段时应 fallback 到字段扫描")
    void shouldFallbackToFieldScanWhenNoCclField() throws Exception {
        ClassLoader customCL = new ClassLoader() {
        };
        TargetWithOtherLoaderField target = new TargetWithOtherLoaderField(customCL);

        Method m = RmiTargetUnloadHook.class.getDeclaredMethod(
                "extractTargetClassLoader", Object.class);
        m.setAccessible(true);
        ClassLoader result = (ClassLoader) m.invoke(hook, target);
        assertSame(customCL, result);
    }

    @Test
    @DisplayName("extractTargetClassLoader 在无 ClassLoader 字段时应返回 null")
    void shouldReturnNullWhenNoClassLoaderField() throws Exception {
        TargetWithoutLoader target = new TargetWithoutLoader("test");

        Method m = RmiTargetUnloadHook.class.getDeclaredMethod(
                "extractTargetClassLoader", Object.class);
        m.setAccessible(true);
        ClassLoader result = (ClassLoader) m.invoke(hook, target);
        assertNull(result);
    }

    @Test
    @DisplayName("extractTargetClassLoader 对普通 Object 应返回 null")
    void shouldReturnNullForPlainObject() throws Exception {
        Method m = RmiTargetUnloadHook.class.getDeclaredMethod(
                "extractTargetClassLoader", Object.class);
        m.setAccessible(true);
        ClassLoader result = (ClassLoader) m.invoke(hook, new Object());
        assertNull(result);
    }

    // ==================== isLoadedBy ====================

    @Test
    @DisplayName("isLoadedBy null CL 应返回 false")
    void isLoadedByShouldReturnFalseForNullCl() throws Exception {
        Method m = RmiTargetUnloadHook.class.getDeclaredMethod(
                "isLoadedBy", ClassLoader.class, ClassLoader.class);
        m.setAccessible(true);
        assertFalse((boolean) m.invoke(hook, (Object) null, new ClassLoader() {
        }));
    }

    @Test
    @DisplayName("isLoadedBy 当 CL == target 时应返回 true")
    void isLoadedByShouldReturnTrueWhenClEqualsTarget() throws Exception {
        ClassLoader customCL = new ClassLoader() {
        };
        Method m = RmiTargetUnloadHook.class.getDeclaredMethod(
                "isLoadedBy", ClassLoader.class, ClassLoader.class);
        m.setAccessible(true);
        assertTrue((boolean) m.invoke(hook, customCL, customCL));
    }

    @Test
    @DisplayName("isLoadedBy 当 CL 的父链包含 target 时应返回 true")
    void isLoadedByShouldReturnTrueWhenParentChainContainsTarget() throws Exception {
        ClassLoader parent = new ClassLoader() {
        };
        ClassLoader child = new ClassLoader(parent) {
        };
        Method m = RmiTargetUnloadHook.class.getDeclaredMethod(
                "isLoadedBy", ClassLoader.class, ClassLoader.class);
        m.setAccessible(true);
        assertTrue((boolean) m.invoke(hook, child, parent));
    }

    @Test
    @DisplayName("isLoadedBy 当 CL 父链不包含 target 时应返回 false")
    void isLoadedByShouldReturnFalseWhenParentChainDoesNotContainTarget() throws Exception {
        ClassLoader target = new ClassLoader() {
        };
        ClassLoader other = new ClassLoader() {
        };
        Method m = RmiTargetUnloadHook.class.getDeclaredMethod(
                "isLoadedBy", ClassLoader.class, ClassLoader.class);
        m.setAccessible(true);
        assertFalse((boolean) m.invoke(hook, other, target));
    }

    // ==================== clearObjectTable（真实注入路径） ====================

    @Test
    @DisplayName("cleanup 应移除 ObjectTable 中关联目标 CL 的 Target")
    void shouldRemoveTargetFromObjectTable() throws Exception {
        Map<Object, Object> implTable = openObjectTable("implTable");
        assumeTrue(implTable != null,
                "需要 sun.rmi.transport.ObjectTable 且 --add-opens java.rmi/sun.rmi.transport=ALL-UNNAMED");

        ClassLoader customCL = new ClassLoader() {
        };
        FakeRmiTarget fakeTarget = new FakeRmiTarget(customCL);
        Object fakeKey = new Object();
        implTable.put(fakeKey, fakeTarget);

        try {
            int sizeBefore = implTable.size();
            hook.cleanup("ling-rmi-test", customCL);
            // 假 Target 应被移除
            assertFalse(implTable.containsKey(fakeKey),
                    "Fake Target 应在 cleanup 后被移除");
            assertEquals(sizeBefore - 1, implTable.size());
        } finally {
            implTable.remove(fakeKey);
        }
    }

    @Test
    @DisplayName("cleanup 不应移除 ObjectTable 中不关联目标 CL 的 Target")
    void shouldNotRemoveUnrelatedTargetFromObjectTable() throws Exception {
        Map<Object, Object> implTable = openObjectTable("implTable");
        assumeTrue(implTable != null,
                "需要 sun.rmi.transport.ObjectTable 且 --add-opens java.rmi/sun.rmi.transport=ALL-UNNAMED");

        ClassLoader targetCL = new ClassLoader() {
        };
        ClassLoader otherCL = new ClassLoader() {
        };
        FakeRmiTarget unrelatedTarget = new FakeRmiTarget(otherCL);
        Object key = new Object();
        implTable.put(key, unrelatedTarget);

        try {
            hook.cleanup("ling-rmi-test", targetCL);
            // 不关联的 Target 应保留
            assertTrue(implTable.containsKey(key),
                    "不关联目标 CL 的 Target 不应被移除");
        } finally {
            implTable.remove(key);
        }
    }

    @Test
    @DisplayName("cleanup 应同时处理 implTable 和 objTable")
    void shouldProcessBothTables() throws Exception {
        Map<Object, Object> implTable = openObjectTable("implTable");
        Map<Object, Object> objTable = openObjectTable("objTable");
        assumeTrue(implTable != null && objTable != null,
                "需要 sun.rmi.transport.ObjectTable 且 --add-opens java.rmi/sun.rmi.transport=ALL-UNNAMED");

        ClassLoader customCL = new ClassLoader() {
        };
        FakeRmiTarget target1 = new FakeRmiTarget(customCL);
        FakeRmiTarget target2 = new FakeRmiTarget(customCL);
        Object key1 = new Object();
        Object key2 = new Object();
        implTable.put(key1, target1);
        objTable.put(key2, target2);

        try {
            hook.cleanup("ling-rmi-test", customCL);
            assertFalse(implTable.containsKey(key1));
            assertFalse(objTable.containsKey(key2));
        } finally {
            implTable.remove(key1);
            objTable.remove(key2);
        }
    }

    /**
     * 打开 ObjectTable 指定静态表字段。
     * <p>
     * 类不存在、字段不存在或模块未 open 时返回 null，由调用方 assume 跳过。
     * 生产钩子在相同条件下也会降级为 no-op。
     */
    @SuppressWarnings("unchecked")
    private static Map<Object, Object> openObjectTable(String tableName) {
        try {
            Class<?> objectTableClass = Class.forName("sun.rmi.transport.ObjectTable");
            Field tableField = objectTableClass.getDeclaredField(tableName);
            tableField.setAccessible(true);
            return (Map<Object, Object>) tableField.get(null);
        } catch (ClassNotFoundException e) {
            // 非 Oracle/OpenJDK JVM
            return null;
        } catch (Exception e) {
            // InaccessibleObjectException / NoSuchFieldException / IllegalAccessException 等
            return null;
        }
    }
}
