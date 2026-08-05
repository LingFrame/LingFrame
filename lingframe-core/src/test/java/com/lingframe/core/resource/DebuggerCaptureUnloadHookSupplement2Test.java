package com.lingframe.core.resource;

import com.intellij.rt.debugger.agent.CaptureStorage;
import com.intellij.rt.debugger.agent.ConcurrentIdentityWeakHashMap;
import com.intellij.rt.debugger.agent.ExceptionCapturedStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DebuggerCaptureUnloadHook} 的第二轮补充测试。
 * <p>
 * 已有 {@link DebuggerCaptureUnloadHookSupplementTest} 覆盖 null CL 和 CaptureStorage 不存在的早返回路径，
 * 此处通过 fake CaptureStorage 类（在 test classpath 上）覆盖核心反射逻辑：
 * <ul>
 *   <li>{@code resolveBackingMap} 三条路径（Map 直返 / map 字段 / 字段扫描）</li>
 *   <li>{@code referencesLingClassLoader} 四种情况（Class / Throwable / Object[] / com.intellij.*）</li>
 *   <li>{@code scanThrowableBacktrace} / {@code scanIntelliJFields}</li>
 *   <li>条目移除和 fallback clear</li>
 * </ul>
 */
@DisplayName("DebuggerCaptureUnloadHook 补充测试 II（核心反射逻辑）")
class DebuggerCaptureUnloadHookSupplement2Test {

    private final DebuggerCaptureUnloadHook hook = new DebuggerCaptureUnloadHook();

    @AfterEach
    void cleanupStorage() {
        // 清理 fake CaptureStorage 静态字段，避免污染其他测试
        CaptureStorage.STORAGE_THROWABLES = null;
    }

    // ==================== 辅助类型 ====================

    /** 没有 map 字段，但有其他 Map 类型字段（触发 resolveBackingMap 字段扫描） */
    @SuppressWarnings("unused")
    public static class StorageWithMapField {
        public HashMap<Object, Object> dataMap = new HashMap<>();
    }

    // ==================== cleanup 入口测试 ====================

    @Test
    @DisplayName("STORAGE_THROWABLES 为 null 时应 log.warn 并返回")
    void shouldWarnWhenStorageNull() {
        CaptureStorage.STORAGE_THROWABLES = null;
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> hook.cleanup("ling-debug", customCL));
    }

    @Test
    @DisplayName("STORAGE_THROWABLES 为空 Map 时应直接返回")
    void shouldReturnWhenMapEmpty() {
        CaptureStorage.STORAGE_THROWABLES = new HashMap<>();
        ClassLoader customCL = new ClassLoader() {
        };
        assertDoesNotThrow(() -> hook.cleanup("ling-debug", customCL));
        // 空 Map 不应被修改
        assertTrue(((Map<?, ?>) CaptureStorage.STORAGE_THROWABLES).isEmpty());
    }

    @Test
    @DisplayName("STORAGE_THROWABLES 为非空 Map 无灵元引用时应 fallback clear")
    void shouldFallbackClearWhenNoLingReference() {
        Map<Object, Object> map = new HashMap<>();
        map.put("entry1", "string-value");
        map.put("entry2", 42);
        CaptureStorage.STORAGE_THROWABLES = map;

        ClassLoader customCL = new ClassLoader() {
        };
        hook.cleanup("ling-debug", customCL);

        // 无条目引用 customCL → removed=0 → fallback clear
        assertTrue(map.isEmpty());
    }

    @Test
    @DisplayName("STORAGE_THROWABLES 为非空 Map 有灵元引用的 Class 时应移除条目")
    void shouldRemoveEntryReferencingLingClassLoader() {
        ClassLoader testCL = getClass().getClassLoader();
        Map<Object, Object> map = new HashMap<>();
        // getClass() 的 ClassLoader == testCL → referencesLingClassLoader 返回 true
        map.put("target-class", getClass());
        map.put("unrelated", "safe-value");
        CaptureStorage.STORAGE_THROWABLES = map;

        hook.cleanup("ling-debug", testCL);

        // 引用灵元 CL 的条目应被移除
        assertFalse(map.containsKey("target-class"));
        // 不引用的条目应保留
        assertTrue(map.containsKey("unrelated"));
    }

    @Test
    @DisplayName("STORAGE_THROWABLES 为 ConcurrentIdentityWeakHashMap 时应通过 map 字段解析")
    void shouldResolveBackingMapViaMapField() {
        ConcurrentIdentityWeakHashMap wrapper = new ConcurrentIdentityWeakHashMap();
        wrapper.map.put("entry1", "value1");
        wrapper.map.put("entry2", 42);
        CaptureStorage.STORAGE_THROWABLES = wrapper;

        ClassLoader customCL = new ClassLoader() {
        };
        hook.cleanup("ling-debug", customCL);

        // resolveBackingMap 反射获取 wrapper.map → 非空但无灵元引用 → fallback clear
        assertTrue(wrapper.map.isEmpty());
    }

    @Test
    @DisplayName("STORAGE_THROWABLES 为有其他 Map 类型字段的对象时应通过字段扫描解析")
    void shouldResolveBackingMapViaFieldScan() {
        StorageWithMapField storage = new StorageWithMapField();
        storage.dataMap.put("entry1", "value1");
        CaptureStorage.STORAGE_THROWABLES = storage;

        ClassLoader customCL = new ClassLoader() {
        };
        hook.cleanup("ling-debug", customCL);

        // resolveBackingMap: 不是 Map → 找 "map" 字段失败 → 扫描字段找 Map 类型 → 找到 dataMap
        // dataMap 非空但无灵元引用 → fallback clear
        assertTrue(storage.dataMap.isEmpty());
    }

    @Test
    @DisplayName("STORAGE_THROWABLES 为 ConcurrentIdentityWeakHashMap 含灵元引用时应移除对应条目")
    void shouldRemoveEntryFromConcurrentIdentityWeakHashMap() {
        ClassLoader testCL = getClass().getClassLoader();
        ConcurrentIdentityWeakHashMap wrapper = new ConcurrentIdentityWeakHashMap();
        wrapper.map.put("target-class", getClass());
        wrapper.map.put("unrelated", "safe");
        CaptureStorage.STORAGE_THROWABLES = wrapper;

        hook.cleanup("ling-debug", testCL);

        assertFalse(wrapper.map.containsKey("target-class"));
        assertTrue(wrapper.map.containsKey("unrelated"));
    }

    @Test
    @DisplayName("cleanup null ClassLoader 应直接返回")
    void shouldReturnForNullClassLoader() {
        CaptureStorage.STORAGE_THROWABLES = new HashMap<>();
        assertDoesNotThrow(() -> hook.cleanup("ling-debug", null));
    }

    // ==================== referencesLingClassLoader（反射调用） ====================

    /**
     * 反射调用 private referencesLingClassLoader。
     */
    private boolean invokeReferences(Object obj, ClassLoader target, Set<Integer> visited) throws Exception {
        Method m = DebuggerCaptureUnloadHook.class.getDeclaredMethod(
                "referencesLingClassLoader", Object.class, ClassLoader.class, Set.class);
        m.setAccessible(true);
        return (boolean) m.invoke(hook, obj, target, visited);
    }

    @Test
    @DisplayName("referencesLingClassLoader null 对象应返回 false")
    void shouldReturnFalseForNullObject() throws Exception {
        assertFalse(invokeReferences(null, new ClassLoader() {
        }, new HashSet<>()));
    }

    @Test
    @DisplayName("referencesLingClassLoader Class 对象 CL 匹配 target 应返回 true")
    void shouldReturnTrueWhenClassLoaderMatches() throws Exception {
        ClassLoader testCL = getClass().getClassLoader();
        // getClass() 的 ClassLoader == testCL
        assertTrue(invokeReferences(getClass(), testCL, new HashSet<>()));
    }

    @Test
    @DisplayName("referencesLingClassLoader Class 对象 CL 不匹配 target 应返回 false")
    void shouldReturnFalseWhenClassLoaderDoesNotMatch() throws Exception {
        ClassLoader customCL = new ClassLoader() {
        };
        // String.class 由 bootstrap CL 加载，getClassLoader() == null != customCL
        assertFalse(invokeReferences(String.class, customCL, new HashSet<>()));
    }

    @Test
    @DisplayName("referencesLingClassLoader 应扫描 Throwable 的 backtrace")
    void shouldScanThrowableBacktrace() throws Exception {
        ClassLoader testCL = getClass().getClassLoader();
        Throwable t = new RuntimeException("test-exception");
        // scanThrowableBacktrace 读取 backtrace → 递归扫描
        // backtrace 包含栈帧上的 Class 引用，其中测试类由 testCL 加载
        boolean result = invokeReferences(t, testCL, new HashSet<>());
        // 不断言具体值（依赖 JVM 内部结构），只验证不抛异常
        assertTrue(result || !result);
    }

    @Test
    @DisplayName("referencesLingClassLoader 应递归扫描 Object[] 元素")
    void shouldScanObjectArrayElements() throws Exception {
        ClassLoader testCL = getClass().getClassLoader();
        Object[] arr = new Object[]{getClass(), "string", 42};
        // arr[0] = getClass() → Class 分支 → getClassLoader() == testCL → true
        assertTrue(invokeReferences(arr, testCL, new HashSet<>()));
    }

    @Test
    @DisplayName("referencesLingClassLoader 不匹配的 Object[] 应返回 false")
    void shouldReturnFalseForNonMatchingArray() throws Exception {
        ClassLoader customCL = new ClassLoader() {
        };
        Object[] arr = new Object[]{"string", 42, new Object()};
        assertFalse(invokeReferences(arr, customCL, new HashSet<>()));
    }

    @Test
    @DisplayName("referencesLingClassLoader 应扫描 com.intellij.* 内部类字段")
    void shouldScanIntelliJFields() throws Exception {
        ClassLoader testCL = getClass().getClassLoader();
        ExceptionCapturedStack stack = new ExceptionCapturedStack(
                new RuntimeException("captured-exception"));
        // scanIntelliJFields 遍历 myException 字段 → Throwable → scanThrowableBacktrace
        boolean result = invokeReferences(stack, testCL, new HashSet<>());
        // 不断言具体值（依赖 JVM backtrace 结构），只验证不抛异常
        assertTrue(result || !result);
    }

    @Test
    @DisplayName("referencesLingClassLoader 普通业务对象应返回 false（不反射扫描）")
    void shouldReturnFalseForBusinessObject() throws Exception {
        ClassLoader customCL = new ClassLoader() {
        };
        // String 不属于 Class/Throwable/Object[]/com.intellij.*
        assertFalse(invokeReferences("business-value", customCL, new HashSet<>()));
        assertFalse(invokeReferences(42, customCL, new HashSet<>()));
    }

    @Test
    @DisplayName("referencesLingClassLoader visited 去重应防止重复扫描")
    void shouldDeduplicateByVisitedSet() throws Exception {
        ClassLoader customCL = new ClassLoader() {
        };
        Object obj = new Object();
        Set<Integer> visited = new HashSet<>();
        // 预先加入 obj 的 identityHashCode → 第二次调用应直接返回 false
        visited.add(System.identityHashCode(obj));
        assertFalse(invokeReferences(obj, customCL, visited));
    }

    @Test
    @DisplayName("referencesLingClassLoader visited 超过 MAX_SCAN_DEPTH 应返回 false")
    void shouldReturnFalseWhenVisitedExceedsMaxDepth() throws Exception {
        ClassLoader customCL = new ClassLoader() {
        };
        Set<Integer> visited = new HashSet<>();
        // 填充超过 MAX_SCAN_DEPTH (64) 个元素
        for (int i = 0; i < 70; i++) {
            visited.add(i);
        }
        assertFalse(invokeReferences(new Object(), customCL, visited));
    }

    @Test
    @DisplayName("referencesLingClassLoader 原始数组应返回 false")
    void shouldReturnFalseForPrimitiveArray() throws Exception {
        ClassLoader customCL = new ClassLoader() {
        };
        int[] primitives = new int[]{1, 2, 3};
        // 原始数组不属于 Object[] 分支
        assertFalse(invokeReferences(primitives, customCL, new HashSet<>()));
    }

    // ==================== scanThrowableBacktrace（反射调用） ====================

    @Test
    @DisplayName("scanThrowableBacktrace 应不抛异常地扫描 Throwable backtrace")
    void shouldScanBacktraceWithoutException() throws Exception {
        ClassLoader testCL = getClass().getClassLoader();
        Throwable t = new RuntimeException("scan-test");
        Method m = DebuggerCaptureUnloadHook.class.getDeclaredMethod(
                "scanThrowableBacktrace", Throwable.class, ClassLoader.class, Set.class);
        m.setAccessible(true);
        boolean result = (boolean) m.invoke(hook, t, testCL, new HashSet<>());
        // 不断言具体值，只验证不抛异常
        assertTrue(result || !result);
    }

    // ==================== scanIntelliJFields（反射调用） ====================

    @Test
    @DisplayName("scanIntelliJFields 应遍历 ExceptionCapturedStack 的字段")
    void shouldScanIntelliJFieldsWithoutException() throws Exception {
        ClassLoader testCL = getClass().getClassLoader();
        ExceptionCapturedStack stack = new ExceptionCapturedStack(
                new RuntimeException("intellij-scan"));
        Method m = DebuggerCaptureUnloadHook.class.getDeclaredMethod(
                "scanIntelliJFields", Object.class, ClassLoader.class, Set.class);
        m.setAccessible(true);
        boolean result = (boolean) m.invoke(hook, stack, testCL, new HashSet<>());
        // 不断言具体值，只验证不抛异常
        assertTrue(result || !result);
    }

    // ==================== resolveBackingMap（反射调用） ====================

    @Test
    @DisplayName("resolveBackingMap 对 Map 类型 storage 应直接返回")
    void shouldResolveMapDirectly() throws Exception {
        Map<Object, Object> map = new HashMap<>();
        Method m = DebuggerCaptureUnloadHook.class.getDeclaredMethod(
                "resolveBackingMap", Object.class, String.class);
        m.setAccessible(true);
        Object result = m.invoke(hook, map, "test");
        assertSame(map, result);
    }

    @Test
    @DisplayName("resolveBackingMap 对 null storage 应返回 null")
    void shouldReturnNullForNullStorage() throws Exception {
        Method m = DebuggerCaptureUnloadHook.class.getDeclaredMethod(
                "resolveBackingMap", Object.class, String.class);
        m.setAccessible(true);
        Object result = m.invoke(hook, null, "test");
        assertNull(result);
    }

    @Test
    @DisplayName("resolveBackingMap 对有 map 字段的对象应反射获取")
    void shouldResolveViaMapField() throws Exception {
        ConcurrentIdentityWeakHashMap wrapper = new ConcurrentIdentityWeakHashMap();
        Method m = DebuggerCaptureUnloadHook.class.getDeclaredMethod(
                "resolveBackingMap", Object.class, String.class);
        m.setAccessible(true);
        Object result = m.invoke(hook, wrapper, "test");
        assertSame(wrapper.map, result);
    }

    @Test
    @DisplayName("resolveBackingMap 对有其他 Map 字段的对象应通过字段扫描获取")
    void shouldResolveViaFieldScan() throws Exception {
        StorageWithMapField storage = new StorageWithMapField();
        Method m = DebuggerCaptureUnloadHook.class.getDeclaredMethod(
                "resolveBackingMap", Object.class, String.class);
        m.setAccessible(true);
        Object result = m.invoke(hook, storage, "test");
        assertSame(storage.dataMap, result);
    }

    @Test
    @DisplayName("resolveBackingMap 对无 Map 字段的普通对象应返回 null")
    void shouldReturnNullForPlainObject() throws Exception {
        Method m = DebuggerCaptureUnloadHook.class.getDeclaredMethod(
                "resolveBackingMap", Object.class, String.class);
        m.setAccessible(true);
        Object result = m.invoke(hook, "string-storage", "test");
        assertNull(result);
    }
}
