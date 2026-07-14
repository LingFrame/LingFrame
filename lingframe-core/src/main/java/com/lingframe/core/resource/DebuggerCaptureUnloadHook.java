package com.lingframe.core.resource;

import com.lingframe.core.spi.LingUnloadHook;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * IDE 调试器异常捕获缓存卸载钩子。
 * <p>
 * IntelliJ IDEA 调试模式下，debugger-agent 会拦截所有 Throwable 创建，
 * 将异常的 backtrace 记录到 {@code CaptureStorage.STORAGE_THROWABLES}。
 * backtrace 内部的 Object[] 强引用异常抛出位置栈帧上的 Class 对象，
 * Class 的 classloader 字段引用灵元 ClassLoader，形成阻止 GC 的强引用链：
 * <pre>
 * CaptureStorage.STORAGE_THROWABLES (静态字段, GC root = System Class)
 *   → ConcurrentIdentityWeakHashMap (不实现 Map 接口，包装器)
 *     → .map (ConcurrentHashMap)
 *       → ExceptionCapturedStack.myException (Throwable, 强引用)
 *         → Throwable.backtrace (Object[])
 *           → CGLIB 代理类 / 灵元加载的 Class
 *             → classloader → 灵元 ClassLoader
 * </pre>
 * <p>
 * 注意：{@code ConcurrentIdentityWeakHashMap} 虽以 Weak 命名，但其 value
 * （{@code ExceptionCapturedStack}）强引用 Throwable，故 key 的弱引用语义
 * 无法释放 Throwable，backtrace 持有的 Class 引用常驻，导致 ClassLoader 泄漏。
 * 该类不实现 {@code Map} 接口，内部通过 {@code map} 字段持有 {@code ConcurrentHashMap}，
 * 需通过反射访问底层 Map 才能清理条目。
 * <p>
 * 该泄漏仅在 IDE 调试模式（启动参数含 {@code -javaagent:...debugger-agent.jar}）下发生，
 * 生产环境无此 agent，不受影响。为支持调试模式下的回归测试，需要主动清理。
 * <p>
 * 发现来源：{@code SpringLingContainerUnloadRegressionTest} 第二个用例的 heap dump 分析，
 * 灵元CL 的唯一 GC root 路径即为此引用链。
 */
@Slf4j
public class DebuggerCaptureUnloadHook implements LingUnloadHook {

    private static final String CAPTURE_STORAGE_CLASS = "com.intellij.rt.debugger.agent.CaptureStorage";
    private static final String STORAGE_FIELD = "STORAGE_THROWABLES";

    /** 递归扫描 backtrace / 对象字段的最大深度，防止异常结构导致栈溢出 */
    private static final int MAX_SCAN_DEPTH = 64;

    @Override
    public void cleanup(String lingId, ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        try {
            // debugger-agent 由系统 ClassLoader 加载，不能用灵元 CL 加载（会污染灵元 CL）
            Class<?> captureStorageClass;
            try {
                captureStorageClass = Class.forName(
                        CAPTURE_STORAGE_CLASS, false, ClassLoader.getSystemClassLoader());
            } catch (ClassNotFoundException e) {
                // 非调试模式（无 debugger-agent），无需清理
                return;
            }
            Field storageField = captureStorageClass.getDeclaredField(STORAGE_FIELD);
            storageField.setAccessible(true);
            Object storage = storageField.get(null);
            // CaptureStorage.STORAGE_THROWABLES 类型为 ConcurrentIdentityWeakHashMap，
            // 该类不实现 Map 接口，而是在内部 map 字段中持有 ConcurrentHashMap。
            // 需要两种方式获取底层 Map：直接 instanceof 或反射访问内部 map 字段。
            Map<?, ?> map = resolveBackingMap(storage, lingId);
            if (map == null) {
                log.warn("[{}] CaptureStorage.STORAGE_THROWABLES cannot resolve underlying Map: {}", lingId,
                        storage == null ? "null" : storage.getClass().getName());
                return;
            }
            if (map.isEmpty()) {
                return;
            }
            log.info("[{}] CaptureStorage backing map size={}, type={}", lingId,
                    map.size(), map.getClass().getName());

            int removed = 0;
            int scanned = 0;
            Set<Integer> visited = new HashSet<>();
            Iterator<?> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) it.next();
                Object value = entry.getValue();
                if (value == null) {
                    continue;
                }
                scanned++;
                // 每个条目独立扫描，visited 清空防止跨条目污染
                visited.clear();
                if (referencesLingClassLoader(value, classLoader, visited)) {
                    it.remove();
                    removed++;
                }
            }

            if (removed > 0) {
                log.info("[{}] Cleared {} / {} IntelliJ CaptureStorage entries (debugger throwable cache)",
                        lingId, removed, scanned);
            } else {
                // 兜底：扫描逻辑未匹配到引用灵元CL 的条目，但 map 非空。
                // 可能原因：backtrace 结构因 JVM 版本/调试器版本差异导致扫描失败，
                // 或 ExceptionCapturedStack 的字段结构与预期不符。
                // 直接清空整个 map——STORAGE_THROWABLES 是 IDE 调试器的异常捕获缓存，
                // 清空不影响业务逻辑，调试器会继续捕获新异常。
                log.warn("[{}] CaptureStorage scan removed 0/{} entries, fallback to clear all (size={})",
                        lingId, scanned, map.size());
                map.clear();
            }
        } catch (NoSuchFieldException e) {
            // debugger-agent 版本差异，字段结构已变化
            log.debug("[{}] CaptureStorage.STORAGE_THROWABLES field does not exist (debugger-agent version difference)", lingId);
        } catch (Throwable t) {
            // 调试器内部结构不可访问——不影响生产卸载链路，但需记录便于排查
            log.warn("[{}] CaptureStorage cleanup failed: {}", lingId, t.getMessage(), t);
        }
    }

    /**
     * 将 storage 解析为底层 Map。
     * <p>
     * IntelliJ debugger-agent 的 {@code ConcurrentIdentityWeakHashMap} 不实现 {@code Map} 接口，
     * 而是在内部 {@code map} 字段中持有一个 {@code ConcurrentHashMap}。
     * 本方法按优先级尝试：
     * <ol>
     *   <li>storage 本身实现 Map 接口 → 直接返回</li>
     *   <li>反射读取 storage 的 "map" 字段 → 若为 Map 则返回</li>
     * </ol>
     * 兼容不同版本 debugger-agent 的字段结构差异。
     */
    private Map<?, ?> resolveBackingMap(Object storage, String lingId) {
        if (storage instanceof Map<?, ?>) {
            return (Map<?, ?>) storage;
        }
        if (storage == null) {
            return null;
        }
        try {
            Field mapField = storage.getClass().getDeclaredField("map");
            mapField.setAccessible(true);
            Object inner = mapField.get(storage);
            if (inner instanceof Map<?, ?>) {
                return (Map<?, ?>) inner;
            }
        } catch (NoSuchFieldException e) {
            // 某些版本可能用不同字段名，尝试扫描所有 Map 类型字段
            for (Field f : storage.getClass().getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object inner = f.get(storage);
                        if (inner instanceof Map<?, ?>) {
                            return (Map<?, ?>) inner;
                        }
                    } catch (Exception ignored) {
                        // 继续尝试下一个字段
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[{}] Failed to access inner map of ConcurrentIdentityWeakHashMap: {}",
                    lingId, e.getMessage());
        }
        return null;
    }

    /**
     * 递归检查对象是否（直接或间接）引用灵元CL 加载的 Class。
     * <p>
     * 扫描范围严格控制：
     * <ul>
     *   <li>{@code Class} 对象：直接检查 classloader</li>
     *   <li>{@code Throwable}：扫描私有 {@code backtrace} 字段</li>
     *   <li>{@code Object[]}：递归扫描元素</li>
     *   <li>{@code com.intellij.*} 内部类：反射扫描非静态字段</li>
     * </ul>
     * 其他业务对象不反射扫描，避免误触发了 toString 等副作用。
     */
    private boolean referencesLingClassLoader(Object obj, ClassLoader target, Set<Integer> visited) {
        if (obj == null || visited.size() > MAX_SCAN_DEPTH) {
            return false;
        }
        // 用 identityHashCode 做去重，防止循环引用（backtrace 可能自引用）
        Integer id = System.identityHashCode(obj);
        if (!visited.add(id)) {
            return false;
        }

        // 情况 1：对象本身是 Class，直接判定 classloader
        if (obj instanceof Class) {
            return ((Class<?>) obj).getClassLoader() == target;
        }
        // 情况 2：Throwable，扫描 backtrace（JVM 内部 Object[]，含栈帧 Class 引用）
        if (obj instanceof Throwable) {
            return scanThrowableBacktrace((Throwable) obj, target, visited);
        }
        Class<?> clazz = obj.getClass();
        // 情况 3：对象数组，递归扫描元素
        if (clazz.isArray() && !clazz.getComponentType().isPrimitive()) {
            Object[] arr = (Object[]) obj;
            for (Object element : arr) {
                if (referencesLingClassLoader(element, target, visited)) {
                    return true;
                }
            }
            return false;
        }
        // 情况 4：IntelliJ 内部类（ExceptionCapturedStack 等），反射扫描非静态字段
        // 限制范围避免误扫业务对象
        if (clazz.getName().startsWith("com.intellij.")) {
            return scanIntelliJFields(obj, target, visited);
        }
        return false;
    }

    /**
     * 扫描 Throwable.backtrace（私有 Object[] 字段）。
     * <p>
     * backtrace 是 JVM 内部结构，包含异常抛出时栈帧上的 Class 对象引用，
     * 是本次泄漏的关键持有链（heap dump 显示 backtrace[2][16] → CGLIB 代理类）。
     */
    private boolean scanThrowableBacktrace(Throwable throwable, ClassLoader target, Set<Integer> visited) {
        try {
            Field backtraceField = Throwable.class.getDeclaredField("backtrace");
            backtraceField.setAccessible(true);
            Object backtrace = backtraceField.get(throwable);
            return referencesLingClassLoader(backtrace, target, visited);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 反射扫描 IntelliJ 内部类（含父类）的非静态字段。
     * 用于检查 {@code ExceptionCapturedStack.myException}（Throwable）等字段。
     * 遍历继承链以兼容字段定义在父类的 debugger-agent 版本。
     */
    private boolean scanIntelliJFields(Object obj, ClassLoader target, Set<Integer> visited) {
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    f.setAccessible(true);
                    if (referencesLingClassLoader(f.get(obj), target, visited)) {
                        return true;
                    }
                }
            } catch (Exception e) {
                // 反射失败（security manager 等），降级跳过当前类，继续尝试父类
            }
            c = c.getSuperclass();
        }
        return false;
    }
}
