package com.lingframe.core.resource;

import lombok.extern.slf4j.Slf4j;

import java.lang.ref.Reference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * JVM 清理共享支持：反射探测字段、JDK 版本检测、ClassLoader 关联性判断。
 * <p>
 * 所有字段和方法为包级可见，仅供同包的卸载 Hook 使用。
 */
@Slf4j
class JvmCleanupSupport {

    // =========================================================================
    // JDK 版本
    // =========================================================================

    static final int JDK_VERSION = detectJdkVersion();

    // =========================================================================
    // 反射探测字段（启动时一次性探测，失败为 null）
    // =========================================================================

    /** Thread.target 字段 */
    static final Field THREAD_TARGET_FIELD = probeField(Thread.class, "target");

    /** Thread.inheritedAccessControlContext 字段（Java 24 删除） */
    static final Field THREAD_ACC_FIELD = probeField(Thread.class, "inheritedAccessControlContext");

    /** AccessControlContext.context 字段（Java 24 删除） */
    static final Field ACC_CONTEXT_FIELD = probeAccContextField();

    /** Thread.isVirtual() 方法（Java 21+） */
    static final Method THREAD_IS_VIRTUAL = probeMethod(Thread.class, "isVirtual");

    /** DriverManager.registeredDrivers 字段 */
    static final Field DRIVER_MANAGER_FIELD = probeField(java.sql.DriverManager.class, "registeredDrivers");

    /** Thread.threadLocals 字段 */
    static final Field THREAD_LOCALS_FIELD = probeField(Thread.class, "threadLocals");

    /** Thread.inheritableThreadLocals 字段 */
    static final Field INHERITABLE_THREAD_LOCALS_FIELD = probeField(Thread.class, "inheritableThreadLocals");

    /** ThreadLocalMap.table 字段 */
    static final Field TLM_TABLE_FIELD = probeThreadLocalMapTableField();

    // =========================================================================
    // 能力快照
    // =========================================================================

    static final CapabilitySnapshot CAPABILITY_SNAPSHOT = new CapabilitySnapshot(
            JDK_VERSION,
            THREAD_TARGET_FIELD != null,
            THREAD_ACC_FIELD != null,
            ACC_CONTEXT_FIELD != null,
            THREAD_IS_VIRTUAL != null,
            DRIVER_MANAGER_FIELD != null);

    // =========================================================================
    // 启动日志
    // =========================================================================

    static {
        log.info(
                "JvmCleanupSupport initialized: JDK={}, capabilities=[target={}, acc={}, accContext={}, virtualThread={}]",
                JDK_VERSION,
                THREAD_TARGET_FIELD != null ? "✓" : "✗",
                THREAD_ACC_FIELD != null ? "✓" : "✗",
                ACC_CONTEXT_FIELD != null ? "✓" : "✗",
                THREAD_IS_VIRTUAL != null ? "✓" : "✗");

        if (JDK_VERSION >= 16) {
            List<String> missing = new ArrayList<>();
            if (THREAD_TARGET_FIELD == null)
                missing.add("--add-opens java.base/java.lang=ALL-UNNAMED");
            if (DRIVER_MANAGER_FIELD == null)
                missing.add("--add-opens java.sql/java.sql=ALL-UNNAMED");
            if (!missing.isEmpty()) {
                log.warn("Some cleanup capabilities are limited. Recommended JVM args:\n  {}",
                        String.join("\n  ", missing));
            }
        }
    }

    // =========================================================================
    // CapabilitySnapshot
    // =========================================================================

    static final class CapabilitySnapshot {
        private final int jdkVersion;
        private final boolean threadTargetAccessible;
        private final boolean threadAccessControlAccessible;
        private final boolean accessControlContextAccessible;
        private final boolean virtualThreadIntrospectionAvailable;
        private final boolean driverManagerAccessible;

        CapabilitySnapshot(int jdkVersion,
                           boolean threadTargetAccessible,
                           boolean threadAccessControlAccessible,
                           boolean accessControlContextAccessible,
                           boolean virtualThreadIntrospectionAvailable,
                           boolean driverManagerAccessible) {
            this.jdkVersion = jdkVersion;
            this.threadTargetAccessible = threadTargetAccessible;
            this.threadAccessControlAccessible = threadAccessControlAccessible;
            this.accessControlContextAccessible = accessControlContextAccessible;
            this.virtualThreadIntrospectionAvailable = virtualThreadIntrospectionAvailable;
            this.driverManagerAccessible = driverManagerAccessible;
        }

        int getJdkVersion() { return jdkVersion; }
        boolean isThreadTargetAccessible() { return threadTargetAccessible; }
        boolean isThreadAccessControlAccessible() { return threadAccessControlAccessible; }
        boolean isAccessControlContextAccessible() { return accessControlContextAccessible; }
        boolean isVirtualThreadIntrospectionAvailable() { return virtualThreadIntrospectionAvailable; }
        boolean isDriverManagerAccessible() { return driverManagerAccessible; }

        String toSummary() {
            return String.format(
                    "jdk=%d,target=%s,acc=%s,accContext=%s,virtualThread=%s,driverManager=%s",
                    jdkVersion,
                    threadTargetAccessible,
                    threadAccessControlAccessible,
                    accessControlContextAccessible,
                    virtualThreadIntrospectionAvailable,
                    driverManagerAccessible);
        }
    }

    // =========================================================================
    // 探测方法
    // =========================================================================

    private static int detectJdkVersion() {
        try {
            String version = System.getProperty("java.specification.version");
            if (version.startsWith("1.")) {
                return Integer.parseInt(version.substring(2)); // 1.8 → 8
            }
            return Integer.parseInt(version.split("\\.")[0]); // 17 → 17
        } catch (Exception e) {
            return 8;
        }
    }

    static Field probeField(Class<?> clazz, String fieldName) {
        try {
            Field f = clazz.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            log.debug("Field {}.{} not found (JDK {})", clazz.getSimpleName(), fieldName, JDK_VERSION);
            return null;
        } catch (Exception e) {
            log.debug("Field {}.{} not accessible (JDK {}): {}. " +
                    "Consider adding: --add-opens java.base/{}=ALL-UNNAMED",
                    clazz.getSimpleName(), fieldName, JDK_VERSION, e.getClass().getSimpleName(),
                    clazz.getPackage().getName());
            return null;
        }
    }

    static Method probeMethod(Class<?> clazz, String methodName, Class<?>... paramTypes) {
        try {
            return clazz.getMethod(methodName, paramTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Field probeAccContextField() {
        try {
            Class<?> accClass = Class.forName("java.security.AccessControlContext");
            Field f = accClass.getDeclaredField("context");
            f.setAccessible(true);
            return f;
        } catch (ClassNotFoundException e) {
            // Java 24+: 整个类都删除了
            log.debug("AccessControlContext not found (JDK {}), SecurityManager removed", JDK_VERSION);
            return null;
        } catch (Exception e) {
            log.debug("AccessControlContext.context not accessible (JDK {})", JDK_VERSION);
            return null;
        }
    }

    private static Field probeThreadLocalMapTableField() {
        try {
            Field tlmField = Thread.class.getDeclaredField("threadLocals");
            tlmField.setAccessible(true);

            // 找到 ThreadLocalMap 类
            Class<?> tlmClass = Class.forName("java.lang.ThreadLocal$ThreadLocalMap");
            Field tableField = tlmClass.getDeclaredField("table");
            tableField.setAccessible(true);
            return tableField;
        } catch (Exception e) {
            log.debug("ThreadLocalMap.table not accessible (JDK {}). " +
                    "Consider: --add-opens java.base/java.lang=ALL-UNNAMED", JDK_VERSION);
            return null;
        }
    }

    // =========================================================================
    // 线程工具
    // =========================================================================

    static boolean isVirtualThread(Thread t) {
        if (THREAD_IS_VIRTUAL == null)
            return false;
        try {
            return (Boolean) THREAD_IS_VIRTUAL.invoke(t);
        } catch (Exception e) {
            return false;
        }
    }

    static Thread[] getActiveThreads() {
        ThreadGroup g = Thread.currentThread().getThreadGroup();
        while (g.getParent() != null)
            g = g.getParent();
        Thread[] threads;
        int count;
        do {
            threads = new Thread[g.activeCount() * 2 + 10];
            count = g.enumerate(threads, true);
        } while (count >= threads.length);
        return threads;
    }

    static ClassLoader getContextClassLoaderSafe(Thread t) {
        try {
            return t.getContextClassLoader();
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================================
    // ClassLoader 关联性判断
    // =========================================================================

    static boolean isClassLoaderRelated(Object obj, ClassLoader cl) {
        if (obj == null)
            return false;

        // 核心：直接 ClassLoader 检查
        if (obj.getClass().getClassLoader() == cl)
            return true;
        if (obj instanceof Class && ((Class<?>) obj).getClassLoader() == cl)
            return true;
        if (obj instanceof ClassLoader && obj == cl)
            return true;
        if (obj instanceof Reference) {
            Object referent = ((Reference<?>) obj).get();
            if (referent != null && isClassLoaderRelated(referent, cl)) {
                return true;
            }
        }

        // 深度：处理常见容器类
        if (obj instanceof Iterable) {
            try {
                for (Object item : (Iterable<?>) obj) {
                    if (isClassLoaderRelated(item, cl))
                        return true;
                }
            } catch (Exception e) {
                log.trace("Failed to iterate container: {}", e.getMessage());
            }
        }
        if (obj instanceof Map) {
            try {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                    if (isClassLoaderRelated(entry.getKey(), cl)
                            || isClassLoaderRelated(entry.getValue(), cl))
                        return true;
                }
            } catch (Exception e) {
                log.trace("Failed to iterate map: {}", e.getMessage());
            }
        }
        if (obj.getClass().isArray() && !obj.getClass().getComponentType().isPrimitive()) {
            try {
                int len = Array.getLength(obj);
                for (int i = 0; i < len; i++) {
                    if (isClassLoaderRelated(Array.get(obj, i), cl))
                        return true;
                }
            } catch (Exception e) {
                log.trace("Failed to iterate array: {}", e.getMessage());
            }
        }

        return false;
    }

    static boolean referencesClassLoader(Object acc, ClassLoader cl) {
        if (ACC_CONTEXT_FIELD == null)
            return false; // Java 24+ 或无权限

        try {
            Object arr = ACC_CONTEXT_FIELD.get(acc);
            if (arr == null)
                return false;

            int len = Array.getLength(arr);
            for (int i = 0; i < len; i++) {
                Object pd = Array.get(arr, i);
                // 不直接引用 ProtectionDomain 类，防止 Java 24 ClassNotFoundException
                try {
                    Method getClMethod = pd.getClass().getMethod("getClassLoader");
                    Object pdCl = getClMethod.invoke(pd);
                    if (pdCl == cl)
                        return true;
                } catch (Exception e) {
                    log.trace("Failed to check ProtectionDomain classloader: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.trace("Failed to scan ProtectionDomains: {}", e.getMessage());
        }
        return false;
    }

    static boolean deepReferencesClassLoader(Object obj, ClassLoader cl, int maxDepth) {
        if (obj == null || cl == null || maxDepth <= 0) {
            return false;
        }
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        return deepReferencesClassLoader(obj, cl, maxDepth, visited);
    }

    private static boolean deepReferencesClassLoader(Object obj, ClassLoader cl, int depth,
                                                     IdentityHashMap<Object, Boolean> visited) {
        if (obj == null || cl == null || depth <= 0) {
            return false;
        }
        if (visited.put(obj, Boolean.TRUE) != null) {
            return false;
        }
        if (isClassLoaderRelated(obj, cl)) {
            return true;
        }
        Class<?> type = obj.getClass();
        if (type.isArray() && !type.getComponentType().isPrimitive()) {
            int len = Array.getLength(obj);
            for (int i = 0; i < len; i++) {
                if (deepReferencesClassLoader(Array.get(obj, i), cl, depth - 1, visited)) {
                    return true;
                }
            }
            return false;
        }
        if (obj instanceof Iterable) {
            for (Object item : (Iterable<?>) obj) {
                if (deepReferencesClassLoader(item, cl, depth - 1, visited)) {
                    return true;
                }
            }
            return false;
        }
        if (obj instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                if (deepReferencesClassLoader(entry.getKey(), cl, depth - 1, visited)
                        || deepReferencesClassLoader(entry.getValue(), cl, depth - 1, visited)) {
                    return true;
                }
            }
            return false;
        }

        // 递归扫描字段（避免静态字段）
        Class<?> current = type;
        while (current != null && current != Object.class) {
            Field[] fields = current.getDeclaredFields();
            for (Field f : fields) {
                if ((f.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object fieldValue = f.get(obj);
                    if (deepReferencesClassLoader(fieldValue, cl, depth - 1, visited)) {
                        return true;
                    }
                } catch (Exception e) {
                    log.trace("Failed to inspect field {}.{}: {}", current.getSimpleName(), f.getName(), e.getMessage());
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    // =========================================================================
    // ClassLoader 安全校验
    // =========================================================================

    /**
     * 检查 ClassLoader 是否安全可清理（非 null、非系统、非平台）。
     *
     * @return true 表示安全，可以继续清理
     */
    static boolean isSafeToCleanup(String lingId, ClassLoader classLoader) {
        if (classLoader == null || classLoader == ClassLoader.getSystemClassLoader()
                || (ClassLoader.getSystemClassLoader() != null && classLoader == ClassLoader.getSystemClassLoader().getParent())) {
            log.warn("[{}] Skip resource cleanup for null, system or platform ClassLoader to protect lingcore environment.", lingId);
            return false;
        }
        return true;
    }

    private JvmCleanupSupport() {
        // 工具类，禁止实例化
    }
}
