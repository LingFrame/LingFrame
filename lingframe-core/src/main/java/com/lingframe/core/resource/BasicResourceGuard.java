package com.lingframe.core.resource;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.spi.ResourceGuard;
import lombok.extern.slf4j.Slf4j;

import java.lang.ref.Reference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

@Slf4j
public class BasicResourceGuard implements ResourceGuard {

    public static final class CapabilitySnapshot {
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

        public int getJdkVersion() {
            return jdkVersion;
        }

        public boolean isThreadTargetAccessible() {
            return threadTargetAccessible;
        }

        public boolean isThreadAccessControlAccessible() {
            return threadAccessControlAccessible;
        }

        public boolean isAccessControlContextAccessible() {
            return accessControlContextAccessible;
        }

        public boolean isVirtualThreadIntrospectionAvailable() {
            return virtualThreadIntrospectionAvailable;
        }

        public boolean isDriverManagerAccessible() {
            return driverManagerAccessible;
        }

        public String toSummary() {
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

    private static final int JDK_VERSION = detectJdkVersion();

    /**
     * Thread.target 字段是否可用
     */
    private static final Field THREAD_TARGET_FIELD = probeField(Thread.class, "target");

    /**
     * Thread.inheritedAccessControlContext 字段是否可用（Java 24 删除）
     */
    private static final Field THREAD_ACC_FIELD = probeField(Thread.class, "inheritedAccessControlContext");

    /**
     * AccessControlContext.context 字段是否可用（Java 24 删除）
     */
    private static final Field ACC_CONTEXT_FIELD = probeAccContextField();

    /**
     * Thread.isVirtual() 方法是否可用（Java 21+）
     */
    private static final Method THREAD_IS_VIRTUAL = probeMethod(Thread.class, "isVirtual");

    /**
     * DriverManager.registeredDrivers 字段是否可用
     */
    private static final Field DRIVER_MANAGER_FIELD = probeField(DriverManager.class, "registeredDrivers");
    private static final CapabilitySnapshot CAPABILITY_SNAPSHOT = new CapabilitySnapshot(
            JDK_VERSION,
            THREAD_TARGET_FIELD != null,
            THREAD_ACC_FIELD != null,
            ACC_CONTEXT_FIELD != null,
            THREAD_IS_VIRTUAL != null,
            DRIVER_MANAGER_FIELD != null);

    private final EventBus eventBus;

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

    /**
     * 启动时探测字段是否可访问，失败返回 null
     * 避免运行时反复尝试失败的反射
     */
    private static Field probeField(Class<?> clazz, String fieldName) {
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

    private static Method probeMethod(Class<?> clazz, String methodName, Class<?>... paramTypes) {
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

    /**
     * 判断线程是否为虚拟线程（Java 21+）
     */
    private static boolean isVirtualThread(Thread t) {
        if (THREAD_IS_VIRTUAL == null)
            return false;
        try {
            return (Boolean) THREAD_IS_VIRTUAL.invoke(t);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 启动时打印一次 JDK 适配信息
     */
    static {
        log.info(
                "BasicResourceGuard initialized: JDK={}, capabilities=[target={}, acc={}, accContext={}, virtualThread={}]",
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
    // 核心逻辑
    // =========================================================================

    public BasicResourceGuard() {
        this(null);
    }

    public BasicResourceGuard(EventBus eventBus) {
        this.eventBus = eventBus;
        publishCapabilitySnapshot("BasicResourceGuard");
    }

    public CapabilitySnapshot getCapabilitySnapshot() {
        return CAPABILITY_SNAPSHOT;
    }

    @Override
    public void cleanup(String lingId, ClassLoader classLoader) {
        log.info("[{}] Starting resource cleanup (JDK {})...", lingId, JDK_VERSION);
        log.info("[{}] Target ClassLoader: {}@{}", lingId,
                classLoader.getClass().getSimpleName(),
                Integer.toHexString(System.identityHashCode(classLoader)));

        // 诊断：打印可疑线程
        diagnoseSuspectThreads(lingId, classLoader);

        // 1. MySQL 清理线程
        cleanupMySqlThread(lingId, classLoader);

        // 2. JDBC 驱动反注册
        int count = deregisterJdbcDrivers(lingId, classLoader);
        if (count > 0) {
            log.info("[{}] Deregistered {} JDBC driver(s)", lingId, count);
        } else {
            log.debug("[{}] No JDBC drivers found to deregister", lingId);
        }

        // 3. 线程引用清理
        clearThreadReferences(lingId, classLoader);

        // 4. ThreadLocal 清理
        clearThreadLocals(lingId, classLoader);

        // 5. 清理灵元注册的 ShutdownHook（按 ClassLoader 归属全量匹配）
        int hookCount = clearShutdownHooks(lingId, classLoader);
        if (hookCount > 0) {
            log.info("[{}] Removed {} shutdown hook(s)", lingId, hookCount);
        }

        log.info("[{}] Resource cleanup completed", lingId);
    }

    public void shutdown() {
    }

    protected void publishCapabilitySnapshot(String runtime) {
        if (eventBus == null) {
            return;
        }
        eventBus.publish(new MonitoringEvents.ResourceCleanupCapabilityEvent(
                runtime,
                CAPABILITY_SNAPSHOT.getJdkVersion(),
                CAPABILITY_SNAPSHOT.isThreadTargetAccessible(),
                CAPABILITY_SNAPSHOT.isThreadAccessControlAccessible(),
                CAPABILITY_SNAPSHOT.isAccessControlContextAccessible(),
                CAPABILITY_SNAPSHOT.isVirtualThreadIntrospectionAvailable(),
                CAPABILITY_SNAPSHOT.isDriverManagerAccessible(),
                CAPABILITY_SNAPSHOT.toSummary()));
    }

    // =========================================================================
    // 诊断
    // =========================================================================

    private void diagnoseSuspectThreads(String lingId, ClassLoader classLoader) {
        Thread[] allThreads = getActiveThreads();
        for (Thread t : allThreads) {
            if (t == null)
                continue;

            ClassLoader tccl = getContextClassLoaderSafe(t);
            boolean isMysql = t.getName().contains("mysql") || t.getName().contains("MySQL");
            boolean isSameCL = (tccl == classLoader);

            if (isMysql || isSameCL) {
                String tcclInfo = tccl == null ? "null"
                        : tccl.getClass().getSimpleName() + "@" +
                                Integer.toHexString(System.identityHashCode(tccl));
                String extra = isVirtualThread(t) ? ", virtual=true" : "";

                log.warn("[{}] SUSPECT THREAD: name='{}', state={}, daemon={}, contextCL={}, sameCL={}{}",
                        lingId, t.getName(), t.getState(), t.isDaemon(),
                        tcclInfo, isSameCL, extra);
            }
        }
    }

    // =========================================================================
    // ShutdownHook 清理（按 ClassLoader 归属全量匹配）
    // =========================================================================

    /**
     * 清理由目标 ClassLoader 注册的所有 ShutdownHook。
     * <p>
     * 匹配规则（二选一）：
     * <ul>
     *   <li>Hook 线程的 contextClassLoader 与目标 ClassLoader 相同</li>
     *   <li>Hook 线程的 Class（即 Runnable 的实际类）由目标 ClassLoader 加载</li>
     * </ul>
     * 第二条规则覆盖灵元自定义 Hook 名的场景——只要 Hook 类由灵元 ClassLoader 加载，
     * 无论线程名叫什么，都会被清理。
     *
     * @return 移除的 Hook 数量
     */
    private int clearShutdownHooks(String lingId, ClassLoader classLoader) {
        try {
            Class<?> hooksClass = Class.forName("java.lang.ApplicationShutdownHooks");
            Field hooksField = hooksClass.getDeclaredField("hooks");
            hooksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Thread, Thread> hooks = (Map<Thread, Thread>) hooksField.get(null);
            if (hooks == null || hooks.isEmpty()) {
                return 0;
            }
            List<Thread> toRemove = new CopyOnWriteArrayList<>();
            hooks.forEach((hook, value) -> {
                if (hook == null) {
                    return;
                }
                // 规则 1：Hook 线程的 contextClassLoader 属于目标灵元
                ClassLoader tccl = hook.getContextClassLoader();
                if (tccl == classLoader) {
                    toRemove.add(hook);
                    return;
                }
                // 规则 2：Hook 的 Runnable 类由目标灵元 ClassLoader 加载
                // 覆盖灵元自定义 Hook 名的场景（如 "my-cleanup-hook"）
                ClassLoader hookClassCL = hook.getClass().getClassLoader();
                if (hookClassCL == classLoader) {
                    toRemove.add(hook);
                }
            });
            for (Thread hook : toRemove) {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                    log.info("[{}] Removed shutdown hook: {} (class={})",
                            lingId, hook.getName(), hook.getClass().getName());
                } catch (IllegalStateException e) {
                    // JVM 正在退出，无法移除
                    log.debug("[{}] Cannot remove shutdown hook during JVM shutdown: {}",
                            lingId, hook.getName());
                } catch (Exception e) {
                    log.debug("[{}] Failed to remove shutdown hook: {}", lingId, e.getMessage());
                }
            }
            return toRemove.size();
        } catch (Exception e) {
            log.debug("[{}] Shutdown hook cleanup failed: {}", lingId, e.getMessage());
            return 0;
        }
    }

    // =========================================================================
    // MySQL 清理
    // =========================================================================

    private void cleanupMySqlThread(String lingId, ClassLoader classLoader) {
        log.info("[{}] Looking for MySQL cleanup thread...", lingId);

        // Step 1: 通过 MySQL API 关闭
        invokeMySqlCheckedShutdown(lingId, classLoader);

        // Step 2: 扫描线程
        Thread[] threads = getActiveThreads();
        boolean found = false;

        for (Thread t : threads) {
            if (t == null)
                continue;

            // 跳过虚拟线程（MySQL 不会用虚拟线程）
            if (isVirtualThread(t))
                continue;

            if (!isMySqlCleanupThread(t))
                continue;
            if (!isThreadRelatedToClassLoader(t, classLoader)) {
                log.debug("[{}] MySQL thread {} not related to this CL, skipping", lingId, t.getName());
                continue;
            }

            found = true;
            log.info("[{}] Found MySQL cleanup thread: {}, state={}, alive={}",
                    lingId, t.getName(), t.getState(), t.isAlive());

            // Step 3: 关闭 Executor
            shutdownExecutorViaThread(lingId, t, classLoader);

            // Step 4: 中断 + 等待
            t.interrupt();
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("[{}] After join: alive={}", lingId, t.isAlive());

            // Step 5: 清理线程上的所有引用
            clearAllThreadReferences(lingId, t, classLoader);
        }

        if (!found) {
            log.info("[{}] No related MySQL cleanup thread found", lingId);
        }
        log.info("[{}] MySQL cleanup complete", lingId);
    }

    private boolean isMySqlCleanupThread(Thread t) {
        String name = t.getName();
        return name.contains("mysql-cj-abandoned-connection-cleanup")
                || name.contains("Abandoned connection cleanup");
    }

    /**
     * 检查线程是否关联目标 ClassLoader
     * 策略：contextClassLoader → inheritedAccessControlContext（如果可用）
     */
    private boolean isThreadRelatedToClassLoader(Thread t, ClassLoader classLoader) {
        // 1. contextClassLoader
        if (getContextClassLoaderSafe(t) == classLoader) {
            return true;
        }

        // 2. inheritedAccessControlContext（Java < 24）
        if (THREAD_ACC_FIELD != null) {
            try {
                Object acc = THREAD_ACC_FIELD.get(t);
                if (acc != null && referencesClassLoader(acc, classLoader)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        // 3. target 的 ClassLoader
        if (THREAD_TARGET_FIELD != null) {
            try {
                Object target = THREAD_TARGET_FIELD.get(t);
                if (target != null && target.getClass().getClassLoader() == classLoader) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        return false;
    }

    private void invokeMySqlCheckedShutdown(String lingId, ClassLoader classLoader) {
        String[] classNames = {
                "com.mysql.cj.jdbc.AbandonedConnectionCleanupThread",
                "com.mysql.jdbc.AbandonedConnectionCleanupThread"
        };
        for (String className : classNames) {
            try {
                Class<?> cls = Class.forName(className, true, classLoader);
                // 确认是目标 CL 加载的，防止回退到 parent
                if (cls.getClassLoader() != classLoader)
                    continue;

                cls.getMethod("checkedShutdown").invoke(null);
                log.info("[{}] checkedShutdown() called via {}", lingId, className);
                return;
            } catch (Exception e) {
                log.debug("[{}] checkedShutdown via {} failed: {}", lingId, className, e.getMessage());
            }
        }
    }

    /**
     * 清理线程上的所有引用（contextCL、ACC、target）
     */
    private void clearAllThreadReferences(String lingId, Thread t, ClassLoader classLoader) {
        // contextClassLoader
        try {
            if (t.getContextClassLoader() == classLoader) {
                t.setContextClassLoader(null);
                log.info("[{}] Cleared contextClassLoader on thread: {}", lingId, t.getName());
            }
        } catch (Exception ignored) {
        }

        // inheritedAccessControlContext（Java < 24）
        clearThreadAccField(lingId, t, classLoader);

        // target（Java < 21 或平台线程）
        clearThreadTargetField(lingId, t, classLoader);
    }

    // =========================================================================
    // Executor 关闭
    // =========================================================================

    private void shutdownExecutorViaThread(String lingId, Thread thread, ClassLoader classLoader) {
        // 方法1: Thread.target → Worker.this$0 → ThreadPoolExecutor
        if (THREAD_TARGET_FIELD != null) {
            try {
                Object worker = THREAD_TARGET_FIELD.get(thread);
                if (worker != null) {
                    Field this0Field = worker.getClass().getDeclaredField("this$0");
                    this0Field.setAccessible(true);
                    Object executor = this0Field.get(worker);
                    if (executor instanceof ExecutorService) {
                        ((ExecutorService) executor).shutdownNow();
                        log.info("[{}] Shut down executor via Worker.this$0", lingId);
                        return;
                    }
                }
            } catch (Exception e) {
                log.debug("[{}] Worker.this$0 approach failed: {}", lingId, e.getMessage());
            }
        }

        // 方法2: MySQL 类的静态字段
        shutdownMySqlExecutorViaStaticField(lingId, classLoader);
    }

    private void shutdownMySqlExecutorViaStaticField(String lingId, ClassLoader classLoader) {
        String[] classNames = {
                "com.mysql.cj.jdbc.AbandonedConnectionCleanupThread",
                "com.mysql.jdbc.AbandonedConnectionCleanupThread"
        };
        String[] fieldNames = {
                "cleanupThreadExecutorService", "executorService", "cleanupThreadExcecutorService"
        };

        for (String className : classNames) {
            try {
                Class<?> cls = Class.forName(className, true, classLoader);
                if (cls.getClassLoader() != classLoader)
                    continue;

                for (String fieldName : fieldNames) {
                    try {
                        Field f = cls.getDeclaredField(fieldName);
                        f.setAccessible(true);
                        Object exec = f.get(null);
                        if (exec instanceof ExecutorService) {
                            ((ExecutorService) exec).shutdownNow();
                            log.info("[{}] Shut down executor via {}.{}", lingId, className, fieldName);
                            return;
                        }
                    } catch (NoSuchFieldException ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }

        log.warn("[{}] Could not shut down MySQL executor", lingId);
    }

    // =========================================================================
    // JDBC 驱动反注册
    // =========================================================================

    private int deregisterJdbcDrivers(String lingId, ClassLoader classLoader) {
        // 1. 优先尝试让 Ling 自己清理（通过 LingClassLoader 加载的 Helper 避开 CallerSensitive 和 JDK17 反射限制）
        try {
            Class<?> helperClass = classLoader.loadClass("com.lingframe.starter.util.JdbcCleanupHelper");
            // 只有当 Helper 真的是由该 ClassLoader 加载的，才调用，否则跳过
            if (helperClass.getClassLoader() == classLoader) {
                Method cleanupMethod = helperClass.getMethod("deregisterAll");
                int count = (Integer) cleanupMethod.invoke(null);
                if (count > 0) {
                    log.info("[{}] Deregistered {} JDBC driver(s) via JdbcCleanupHelper", lingId, count);
                    return count;
                }
            }
        } catch (Throwable e) {
            log.debug("[{}] JdbcCleanupHelper not found or failed: {}", lingId, e.getMessage());
        }

        // 2. 然后尝试强悍的反射清理（绕过 Caller检查，但需要 add-opens java.sql/java.sql=ALL-UNNAMED）
        if (DRIVER_MANAGER_FIELD != null) {
            int count = deregisterJdbcDriversReflection(lingId, classLoader);
            if (count > 0)
                return count;
        }

        // 3. 兜底：如果都没有成功，尝试使用 Public API
        // 注意：这里的调用者是 HostClassLoader，将永远找不到 LingCL 的 Driver，但为了极少数非隔离场景留着
        return deregisterJdbcDriversPublicApi(lingId, classLoader);
    }

    /**
     * Java 9+ 公开 API
     */
    private int deregisterJdbcDriversPublicApi(String lingId, ClassLoader classLoader) {
        int count = 0;
        List<Driver> toDeregister = new ArrayList<>();

        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver d = drivers.nextElement();
            if (d.getClass().getClassLoader() == classLoader) {
                toDeregister.add(d);
            }
        }

        for (Driver d : toDeregister) {
            try {
                DriverManager.deregisterDriver(d);
                count++;
                log.info("[{}] Deregistered: {}", lingId, d.getClass().getName());
            } catch (Exception e) {
                log.warn("[{}] Failed to deregister {}: {}", lingId, d.getClass().getName(), e.getMessage());
            }
        }
        return count;
    }

    /**
     * Java 8 反射方式（绕过 caller ClassLoader 检查）
     */
    private int deregisterJdbcDriversReflection(String lingId, ClassLoader classLoader) {
        int count = 0;
        try {
            @SuppressWarnings("unchecked")
            CopyOnWriteArrayList<Object> drivers = (CopyOnWriteArrayList<Object>) DRIVER_MANAGER_FIELD.get(null);
            if (drivers == null)
                return 0;

            List<Object> toRemove = new ArrayList<>();
            Field driverField = null;

            for (Object info : drivers) {
                if (driverField == null) {
                    driverField = info.getClass().getDeclaredField("driver");
                    driverField.setAccessible(true);
                }
                Driver d = (Driver) driverField.get(info);
                if (d != null && d.getClass().getClassLoader() == classLoader) {
                    toRemove.add(info);
                }
            }

            for (Object info : toRemove) {
                Driver d = (Driver) driverField.get(info);
                try {
                    DriverManager.deregisterDriver(d);
                } catch (Exception e) {
                    drivers.remove(info);
                }
                count++;
                log.info("[{}] Deregistered: {}", lingId, d.getClass().getName());
            }
        } catch (Exception e) {
            log.debug("[{}] Driver deregister (reflection) failed: {}", lingId, e.getMessage());
            // 兜底：降级到公开 API
            return deregisterJdbcDriversPublicApi(lingId, classLoader);
        }
        return count;
    }

    // =========================================================================
    // 线程引用清理
    // =========================================================================

    private void clearThreadReferences(String lingId, ClassLoader classLoader) {
        for (Thread t : getActiveThreads()) {
            if (t == null)
                continue;

            // 虚拟线程不处理 target / ACC（结构不同）
            boolean isVirtual = isVirtualThread(t);

            try {
                // contextClassLoader（所有线程类型都有）
                if (t.getContextClassLoader() == classLoader) {
                    t.setContextClassLoader(null);
                    log.debug("[{}] Cleared contextClassLoader on thread: {}", lingId, t.getName());
                }

                if (!isVirtual) {
                    // inheritedAccessControlContext（Java < 24，仅平台线程）
                    clearThreadAccField(lingId, t, classLoader);

                    // target（仅平台线程）
                    clearThreadTargetField(lingId, t, classLoader);
                }

                // 中断由目标 CL 创建的线程
                if (t.getClass().getClassLoader() == classLoader) {
                    log.info("[{}] Interrupting thread created by target CL: {}", lingId, t.getName());
                    t.interrupt();
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 清理 Thread.inheritedAccessControlContext
     * Java 17: @Deprecated(forRemoval=true)
     * Java 24: 已删除
     */
    private void clearThreadAccField(String lingId, Thread t, ClassLoader classLoader) {
        if (THREAD_ACC_FIELD == null)
            return; // Java 24+ 或无权限

        try {
            Object acc = THREAD_ACC_FIELD.get(t);
            if (acc != null && referencesClassLoader(acc, classLoader)) {
                THREAD_ACC_FIELD.set(t, null);
                log.debug("[{}] Cleared inheritedAccessControlContext on thread: {}", lingId, t.getName());
            }
        } catch (Exception e) {
            log.trace("[{}] Failed to clear ACC on thread {}: {}", lingId, t.getName(), e.getMessage());
        }
    }

    /**
     * 清理 Thread.target
     * Java 21+: 虚拟线程没有此字段，平台线程可能重构
     */
    private void clearThreadTargetField(String lingId, Thread t, ClassLoader classLoader) {
        if (THREAD_TARGET_FIELD == null)
            return;

        try {
            Object target = THREAD_TARGET_FIELD.get(t);
            if (target != null && target.getClass().getClassLoader() == classLoader) {
                THREAD_TARGET_FIELD.set(t, null);
                log.debug("[{}] Cleared target on thread: {}", lingId, t.getName());
            }
        } catch (Exception e) {
            log.trace("[{}] Failed to clear target on thread {}: {}", lingId, t.getName(), e.getMessage());
        }
    }

    // =========================================================================
    // AccessControlContext 检查（Java < 24）
    // =========================================================================

    private boolean referencesClassLoader(Object acc, ClassLoader cl) {
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
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // =========================================================================
    // ThreadLocal 清理
    // =========================================================================

    /**
     * ThreadLocal 内部字段缓存（启动时探测一次）
     */
    private static final Field THREAD_LOCALS_FIELD = probeField(Thread.class, "threadLocals");
    private static final Field INHERITABLE_THREAD_LOCALS_FIELD = probeField(Thread.class, "inheritableThreadLocals");
    private static final Field TLM_TABLE_FIELD = probeThreadLocalMapTableField();

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

    private void clearThreadLocals(String lingId, ClassLoader classLoader) {
        if (THREAD_LOCALS_FIELD == null && INHERITABLE_THREAD_LOCALS_FIELD == null) {
            log.warn("[{}] ThreadLocal cleanup not available (JDK {} requires --add-opens)", lingId, JDK_VERSION);
            return;
        }

        int cleaned = 0;
        int scannedThreads = 0;
        for (Thread t : getActiveThreads()) {
            if (t == null)
                continue;

            // Java 21+: 虚拟线程的 ThreadLocal 存储方式不同，跳过
            if (isVirtualThread(t))
                continue;

            scannedThreads++;
            cleaned += clearThreadLocalMap(lingId, t, THREAD_LOCALS_FIELD, classLoader);
            cleaned += clearThreadLocalMap(lingId, t, INHERITABLE_THREAD_LOCALS_FIELD, classLoader);
        }

        if (cleaned > 0) {
            log.info("[{}] Cleared {} ThreadLocal entries", lingId, cleaned);
        } else {
            log.debug("[{}] ThreadLocal scan finished, no entries removed (threads={})", lingId, scannedThreads);
        }
    }

    private int clearThreadLocalMap(String lingId, Thread t, Field mapField, ClassLoader cl) {
        if (mapField == null || TLM_TABLE_FIELD == null)
            return 0;

        int cleaned = 0;
        try {
            Object map = mapField.get(t);
            if (map == null)
                return 0;

            Object[] table = (Object[]) TLM_TABLE_FIELD.get(map);
            if (table == null)
                return 0;

            Field valueField = null;
            Method expungeMethod = null;

            int logged = 0;
            List<String> samples = new ArrayList<>();

            for (Object entry : table) {
                if (entry == null)
                    continue;

                if (valueField == null) {
                    valueField = entry.getClass().getDeclaredField("value");
                    valueField.setAccessible(true);
                }

                Reference<?> ref = (Reference<?>) entry;
                Object key = ref.get();
                Object val = valueField.get(entry);

                if (isClassLoaderRelated(key, cl) || isClassLoaderRelated(val, cl)
                        || deepReferencesClassLoader(key, cl, 3)
                        || deepReferencesClassLoader(val, cl, 3)) {
                    valueField.set(entry, null);
                    ref.clear();
                    cleaned++;
                    if (logged < 3) {
                        samples.add(describeThreadLocalEntry(key, val));
                        logged++;
                    }
                }
            }

            // 触发 expungeStaleEntries
            if (cleaned > 0) {
                if (expungeMethod == null) {
                    try {
                        expungeMethod = map.getClass().getDeclaredMethod("expungeStaleEntries");
                        expungeMethod.setAccessible(true);
                    } catch (NoSuchMethodException ignored) {
                    }
                }
                if (expungeMethod != null) {
                    expungeMethod.invoke(map);
                }
            }
            if (!samples.isEmpty()) {
                log.debug("[{}] ThreadLocal hits on thread {}: {}", lingId, t.getName(), samples);
            }
        } catch (Exception e) {
            log.trace("Failed to clear ThreadLocal on thread {}: {}", t.getName(), e.getMessage());
        }
        return cleaned;
    }

    private String describeThreadLocalEntry(Object key, Object val) {
        String keyType = key == null ? "null" : key.getClass().getName();
        String valType = val == null ? "null" : val.getClass().getName();
        return "key=" + keyType + ", val=" + valType;
    }

    private boolean deepReferencesClassLoader(Object obj, ClassLoader cl, int maxDepth) {
        if (obj == null || cl == null || maxDepth <= 0) {
            return false;
        }
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        return deepReferencesClassLoader(obj, cl, maxDepth, visited);
    }

    private boolean deepReferencesClassLoader(Object obj, ClassLoader cl, int depth,
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
                } catch (Exception ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    // =========================================================================
    // ClassLoader 关联性判断
    // =========================================================================

    private boolean isClassLoaderRelated(Object obj, ClassLoader cl) {
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
            } catch (Exception ignored) {
            }
        }
        if (obj instanceof Map) {
            try {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                    if (isClassLoaderRelated(entry.getKey(), cl)
                            || isClassLoaderRelated(entry.getValue(), cl))
                        return true;
                }
            } catch (Exception ignored) {
            }
        }
        if (obj.getClass().isArray() && !obj.getClass().getComponentType().isPrimitive()) {
            try {
                int len = Array.getLength(obj);
                for (int i = 0; i < len; i++) {
                    if (isClassLoaderRelated(Array.get(obj, i), cl))
                        return true;
                }
            } catch (Exception ignored) {
            }
        }

        return false;
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    private ClassLoader getContextClassLoaderSafe(Thread t) {
        try {
            return t.getContextClassLoader();
        } catch (Exception e) {
            return null;
        }
    }

    private Thread[] getActiveThreads() {
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
}
