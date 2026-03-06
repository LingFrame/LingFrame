package com.lingframe.core.resource;

import com.lingframe.core.spi.ResourceGuard;
import lombok.extern.slf4j.Slf4j;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.AccessControlContext;
import java.security.ProtectionDomain;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class BasicResourceGuard implements ResourceGuard {

    /**
     * 泄漏检测延迟时间（秒）
     */
    private static final int LEAK_DETECTION_DELAY_SECONDS = 5;

    /**
     * 泄漏检测调度器
     * 使用守护线程，不阻止 JVM 退出
     */
    private final ScheduledExecutorService scheduler;

    public BasicResourceGuard() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lingframe-leak-detector");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void cleanup(String lingId, ClassLoader classLoader) {
        log.info("[{}] Starting resource cleanup...", lingId);
        log.info("[{}] Target ClassLoader: {}@{}", lingId,
                classLoader.getClass().getSimpleName(),
                Integer.toHexString(System.identityHashCode(classLoader)));

        // 诊断：打印所有线程
        Thread[] allThreads = getActiveThreads();
        for (Thread t : allThreads) {
            if (t == null)
                continue;
            ClassLoader tccl = null;
            try {
                tccl = t.getContextClassLoader();
            } catch (Exception ignored) {
            }

            String tcclInfo = "null";
            if (tccl != null) {
                tcclInfo = tccl.getClass().getSimpleName() + "@" +
                        Integer.toHexString(System.identityHashCode(tccl));
            }

            // 只打印可疑线程
            boolean isMysql = t.getName().contains("mysql") || t.getName().contains("MySQL");
            boolean isSameCL = (tccl == classLoader);

            if (isMysql || isSameCL) {
                log.warn("[{}] SUSPECT THREAD: name='{}', state={}, daemon={}, " +
                                "contextCL={}, sameCL={}",
                        lingId, t.getName(), t.getState(), t.isDaemon(),
                        tcclInfo, isSameCL);
            }
        }

        // 1. MySQL 清理线程
        cleanupMySqlThread(lingId, classLoader);

        // 2. JDBC 驱动反注册
        int count = deregisterJdbcDrivers(lingId, classLoader);
        if (count > 0) {
            log.info("[{}] Deregistered {} JDBC driver(s)", lingId, count);
        } else {
            log.warn("[{}] No JDBC drivers found to deregister!", lingId);
        }

        // 3. 线程引用清理
        clearThreadReferences(lingId, classLoader);

        // 4. ThreadLocal 清理
        clearThreadLocals(lingId, classLoader);

        log.info("[{}] Resource cleanup completed", lingId);
    }

    @Override
    public void detectLeak(String lingId, ClassLoader classLoader) {
        // 使用 WeakReference 检测 ClassLoader 是否被回收
        WeakReference<ClassLoader> ref = new WeakReference<>(classLoader);

        scheduler.schedule(() -> {
            // 多次 GC 尝试
            for (int i = 0; i < 5; i++) {
                System.gc();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (ref.get() == null) {
                    log.info("✅ [{}] ClassLoader collected successfully (GC round {})", lingId, i + 1);
                    return;
                }
            }
            log.warn("⚠️ [{}] ClassLoader NOT collected after 5 GC rounds! Memory leak confirmed.", lingId);
        }, LEAK_DETECTION_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void cleanupMySqlThread(String lingId, ClassLoader classLoader) {
        log.info("[{}] Looking for MySQL cleanup thread...", lingId);

        // Step 1: 尝试通过 MySQL API 关闭（可能因 CL 已关闭而失败）
        String[] classNames = {
                "com.mysql.cj.jdbc.AbandonedConnectionCleanupThread",
                "com.mysql.jdbc.AbandonedConnectionCleanupThread"
        };
        for (String className : classNames) {
            try {
                Class<?> cls = Class.forName(className, true, classLoader);
                cls.getMethod("checkedShutdown").invoke(null);
                log.info("[{}] checkedShutdown() called via {}", lingId, className);
                break;
            } catch (Exception e) {
                log.debug("[{}] checkedShutdown via {} failed: {}", lingId, className, e.getMessage());
            }
        }

        // Step 2: 扫描所有线程，找到 MySQL 清理线程
        Thread[] threads = getActiveThreads();
        boolean found = false;

        for (Thread t : threads) {
            if (t == null)
                continue;

            // 名称匹配
            if (!t.getName().contains("mysql-cj-abandoned-connection-cleanup") &&
                    !t.getName().contains("Abandoned connection cleanup")) {
                continue;
            }

            // 关联性检查：contextClassLoader 或 ACC 引用目标 CL
            boolean related = (t.getContextClassLoader() == classLoader);
            if (!related) {
                try {
                    Field accField = Thread.class.getDeclaredField("inheritedAccessControlContext");
                    accField.setAccessible(true);
                    Object acc = accField.get(t);
                    if (acc != null && referencesClassLoader(acc, classLoader)) {
                        related = true;
                    }
                } catch (Exception ignored) {
                }
            }

            if (!related) {
                log.debug("[{}] MySQL thread {} not related to this CL, skipping", lingId, t.getName());
                continue;
            }

            found = true;
            log.info("[{}] Found MySQL cleanup thread: {}, state={}, alive={}",
                    lingId, t.getName(), t.getState(), t.isAlive());

            // Step 3: 通过 Thread.target (Worker) 找到 ThreadPoolExecutor 并关闭
            shutdownExecutorViaThread(lingId, t, classLoader);

            // Step 4: 中断线程
            t.interrupt();

            // Step 5: 等待线程退出
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            log.info("[{}] After join: alive={}", lingId, t.isAlive());

            // Step 6: 不管线程是否退出，清理所有引用
            // contextClassLoader
            try {
                if (t.getContextClassLoader() == classLoader) {
                    t.setContextClassLoader(null);
                    log.info("[{}] Cleared contextClassLoader", lingId);
                }
            } catch (Exception ignored) {
            }

            // inheritedAccessControlContext
            try {
                Field accField = Thread.class.getDeclaredField("inheritedAccessControlContext");
                accField.setAccessible(true);
                Object acc = accField.get(t);
                if (acc != null && referencesClassLoader(acc, classLoader)) {
                    accField.set(t, null);
                    log.info("[{}] Cleared inheritedAccessControlContext", lingId);
                }
            } catch (NoSuchFieldException ignored) {
            } catch (Exception ignored) {
            }

            // target
            try {
                Field targetField = Thread.class.getDeclaredField("target");
                targetField.setAccessible(true);
                Object target = targetField.get(t);
                if (target != null && target.getClass().getClassLoader() == classLoader) {
                    targetField.set(t, null);
                    log.info("[{}] Cleared target", lingId);
                }
            } catch (NoSuchFieldException ignored) {
            } catch (Exception ignored) {
            }
        }

        if (!found) {
            log.info("[{}] No related MySQL cleanup thread found", lingId);
        }

        log.info("[{}] MySQL cleanup complete", lingId);
    }

    /**
     * 通过反射找到线程内部的 ThreadPoolExecutor 并关掉
     * 路径: Thread.target → ThreadPoolExecutor$Worker.this$0 → ThreadPoolExecutor
     */
    private void shutdownExecutorViaThread(String lingId, Thread thread, ClassLoader classLoader) {
        // 方法1: 通过 Worker.this$0
        try {
            Field targetField = Thread.class.getDeclaredField("target");
            targetField.setAccessible(true);
            Object worker = targetField.get(thread);
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

        // 方法2: 通过 MySQL 类的静态字段
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
                if (cls.getClassLoader() != classLoader) {
                    log.debug("[{}] {} loaded by different CL, skipping", lingId, className);
                    continue;
                }
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

    private int deregisterJdbcDrivers(String lingId, ClassLoader classLoader) {
        int count = 0;
        try {
            Field f = DriverManager.class.getDeclaredField("registeredDrivers");
            f.setAccessible(true);
            CopyOnWriteArrayList<?> drivers = (CopyOnWriteArrayList<?>) f.get(null);
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
            log.debug("[{}] Driver deregister failed: {}", lingId, e.getMessage());
        }
        return count;
    }

    private void clearThreadReferences(String lingId, ClassLoader classLoader) {
        for (Thread t : getActiveThreads()) {
            if (t == null)
                continue;
            try {
                if (t.getContextClassLoader() == classLoader) {
                    t.setContextClassLoader(null);
                }
                // inheritedAccessControlContext 深度检查
                try {
                    Field accField = Thread.class.getDeclaredField("inheritedAccessControlContext");
                    accField.setAccessible(true);
                    Object acc = accField.get(t);
                    if (acc != null && referencesClassLoader(acc, classLoader)) {
                        accField.set(t, null);
                    }
                } catch (NoSuchFieldException ignored) {
                }

                // target
                try {
                    Field targetField = Thread.class.getDeclaredField("target");
                    targetField.setAccessible(true);
                    Object target = targetField.get(t);
                    if (target != null && target.getClass().getClassLoader() == classLoader) {
                        targetField.set(t, null);
                    }
                } catch (NoSuchFieldException ignored) {
                }

                if (t.getClass().getClassLoader() == classLoader) {
                    t.interrupt();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private boolean referencesClassLoader(Object acc, ClassLoader cl) {
        try {
            Field f = AccessControlContext.class.getDeclaredField("context");
            f.setAccessible(true);
            Object arr = f.get(acc);
            if (arr == null)
                return false;
            int len = Array.getLength(arr);
            for (int i = 0; i < len; i++) {
                Object pd = Array.get(arr, i);
                if (pd instanceof ProtectionDomain) {
                    if (((ProtectionDomain) pd).getClassLoader() == cl)
                        return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void clearThreadLocals(String lingId, ClassLoader classLoader) {
        for (Thread t : getActiveThreads()) {
            if (t == null)
                continue;
            clearThreadLocalMap(t, "threadLocals", classLoader);
            clearThreadLocalMap(t, "inheritableThreadLocals", classLoader);
        }
    }

    private void clearThreadLocalMap(Thread t, String fieldName, ClassLoader cl) {
        try {
            Field mapField = Thread.class.getDeclaredField(fieldName);
            mapField.setAccessible(true);
            Object map = mapField.get(t);
            if (map == null)
                return;

            Field tableField = map.getClass().getDeclaredField("table");
            tableField.setAccessible(true);
            Object[] table = (Object[]) tableField.get(map);
            if (table == null)
                return;

            Field valueField = null;
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

                if (isClassLoaderRelated(key, cl) || isClassLoaderRelated(val, cl)) {
                    valueField.set(entry, null);
                    ref.clear();
                }
            }
            // 触发 expunge
            try {
                Method m = map.getClass().getDeclaredMethod("expungeStaleEntries");
                m.setAccessible(true);
                m.invoke(map);
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
    }

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

        // 深度：处理常见的容器类 (Spring Web 经常放 ArrayList 在 ThreadLocal 里)
        if (obj instanceof Iterable) {
            for (Object item : (Iterable<?>) obj) {
                if (isClassLoaderRelated(item, cl))
                    return true;
            }
        }
        if (obj instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                if (isClassLoaderRelated(entry.getKey(), cl) || isClassLoaderRelated(entry.getValue(), cl))
                    return true;
            }
        }
        if (obj.getClass().isArray()) {
            int len = Array.getLength(obj);
            for (int i = 0; i < len; i++) {
                if (isClassLoaderRelated(Array.get(obj, i), cl))
                    return true;
            }
        }

        return false;
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