package com.lingframe.core.resource;

import com.lingframe.core.spi.LingUnloadHook;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.ref.Reference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 线程引用卸载钩子。
 * <p>
 * 清理目标 ClassLoader 关联的线程引用、MySQL 清理线程、ThreadLocal 条目。
 * 这是最重的清理动作，涵盖：
 * <ul>
 *   <li>可疑线程诊断</li>
 *   <li>MySQL AbandonedConnectionCleanupThread 关闭</li>
 *   <li>线程 contextClassLoader / ACC / target 引用清理</li>
 *   <li>ThreadLocal 条目清理</li>
 * </ul>
 */
@Slf4j
public class ThreadReferenceUnloadHook implements LingUnloadHook {

    @Override
    public void cleanup(String lingId, ClassLoader classLoader) {
        if (!JvmCleanupSupport.isSafeToCleanup(lingId, classLoader)) {
            return;
        }
        log.info("[{}] Starting thread reference cleanup (JDK {})...", lingId, JvmCleanupSupport.JDK_VERSION);
        log.info("[{}] Target ClassLoader: {}@{}", lingId,
                classLoader.getClass().getSimpleName(),
                Integer.toHexString(System.identityHashCode(classLoader)));

        // 诊断：打印可疑线程
        diagnoseSuspectThreads(lingId, classLoader);

        // 1. MySQL 清理线程
        cleanupMySqlThread(lingId, classLoader);

        // 2. 线程引用清理
        clearThreadReferences(lingId, classLoader);

        // 3. ThreadLocal 清理
        clearThreadLocals(lingId, classLoader);

        log.info("[{}] Thread reference cleanup completed", lingId);
    }

    // =========================================================================
    // 诊断
    // =========================================================================

    private void diagnoseSuspectThreads(String lingId, ClassLoader classLoader) {
        Thread[] allThreads = JvmCleanupSupport.getActiveThreads();
        for (Thread t : allThreads) {
            if (t == null)
                continue;

            ClassLoader tccl = JvmCleanupSupport.getContextClassLoaderSafe(t);
            boolean isMysql = t.getName().contains("mysql") || t.getName().contains("MySQL");
            boolean isSameCL = (tccl == classLoader);

            if (isMysql || isSameCL) {
                String tcclInfo = tccl == null ? "null"
                        : tccl.getClass().getSimpleName() + "@" +
                                Integer.toHexString(System.identityHashCode(tccl));
                String extra = JvmCleanupSupport.isVirtualThread(t) ? ", virtual=true" : "";

                log.warn("[{}] SUSPECT THREAD: name='{}', state={}, daemon={}, contextCL={}, sameCL={}{}",
                        lingId, t.getName(), t.getState(), t.isDaemon(),
                        tcclInfo, isSameCL, extra);
            }
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
        Thread[] threads = JvmCleanupSupport.getActiveThreads();
        boolean found = false;

        for (Thread t : threads) {
            if (t == null)
                continue;

            // 跳过虚拟线程（MySQL 不会用虚拟线程）
            if (JvmCleanupSupport.isVirtualThread(t))
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

    private boolean isThreadRelatedToClassLoader(Thread t, ClassLoader classLoader) {
        // 1. contextClassLoader
        if (JvmCleanupSupport.getContextClassLoaderSafe(t) == classLoader) {
            return true;
        }

        // 2. inheritedAccessControlContext（Java < 24）
        if (JvmCleanupSupport.THREAD_ACC_FIELD != null) {
            try {
                Object acc = JvmCleanupSupport.THREAD_ACC_FIELD.get(t);
                if (acc != null && JvmCleanupSupport.referencesClassLoader(acc, classLoader)) {
                    return true;
                }
            } catch (Exception e) {
                log.trace("Failed to check ACC on thread {}: {}", t.getName(), e.getMessage());
            }
        }

        if (JvmCleanupSupport.THREAD_TARGET_FIELD != null) {
            try {
                Object target = JvmCleanupSupport.THREAD_TARGET_FIELD.get(t);
                if (target != null && target.getClass().getClassLoader() == classLoader) {
                    return true;
                }
            } catch (Exception e) {
                log.trace("Failed to check target on thread {}: {}", t.getName(), e.getMessage());
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

    private void clearAllThreadReferences(String lingId, Thread t, ClassLoader classLoader) {
        // contextClassLoader
        try {
            if (t.getContextClassLoader() == classLoader) {
                t.setContextClassLoader(null);
                log.info("[{}] Cleared contextClassLoader on thread: {}", lingId, t.getName());
            }
        } catch (Exception e) {
            log.trace("[{}] Failed to clear contextClassLoader on thread {}: {}", lingId, t.getName(), e.getMessage());
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
        if (JvmCleanupSupport.THREAD_TARGET_FIELD != null) {
            try {
                Object worker = JvmCleanupSupport.THREAD_TARGET_FIELD.get(thread);
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
                    } catch (NoSuchFieldException e) {
                        log.trace("Field {} not found on {}: {}", fieldName, className, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.trace("[{}] Failed to shut down executor via reflection: {}", lingId, e.getMessage());
            }
        }

        log.warn("[{}] Could not shut down MySQL executor", lingId);
    }

    // =========================================================================
    // 线程引用清理
    // =========================================================================

    private void clearThreadReferences(String lingId, ClassLoader classLoader) {
        for (Thread t : JvmCleanupSupport.getActiveThreads()) {
            if (t == null)
                continue;

            // 虚拟线程不处理 target / ACC（结构不同）
            boolean isVirtual = JvmCleanupSupport.isVirtualThread(t);

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
            } catch (Exception e) {
                log.trace("[{}] Failed to scan/interrupt thread {}: {}", lingId, t.getName(), e.getMessage());
            }
        }
    }

    private void clearThreadAccField(String lingId, Thread t, ClassLoader classLoader) {
        if (JvmCleanupSupport.THREAD_ACC_FIELD == null)
            return; // Java 24+ 或无权限

        try {
            Object acc = JvmCleanupSupport.THREAD_ACC_FIELD.get(t);
            if (acc != null && JvmCleanupSupport.referencesClassLoader(acc, classLoader)) {
                JvmCleanupSupport.THREAD_ACC_FIELD.set(t, null);
                log.debug("[{}] Cleared inheritedAccessControlContext on thread: {}", lingId, t.getName());
            }
        } catch (Exception e) {
            log.trace("[{}] Failed to clear ACC on thread {}: {}", lingId, t.getName(), e.getMessage());
        }
    }

    private void clearThreadTargetField(String lingId, Thread t, ClassLoader classLoader) {
        if (JvmCleanupSupport.THREAD_TARGET_FIELD == null)
            return;

        try {
            Object target = JvmCleanupSupport.THREAD_TARGET_FIELD.get(t);
            if (target != null && target.getClass().getClassLoader() == classLoader) {
                JvmCleanupSupport.THREAD_TARGET_FIELD.set(t, null);
                log.debug("[{}] Cleared target on thread: {}", lingId, t.getName());
            }
        } catch (Exception e) {
            log.trace("[{}] Failed to clear target on thread {}: {}", lingId, t.getName(), e.getMessage());
        }
    }

    // =========================================================================
    // ThreadLocal 清理
    // =========================================================================

    private void clearThreadLocals(String lingId, ClassLoader classLoader) {
        if (JvmCleanupSupport.THREAD_LOCALS_FIELD == null && JvmCleanupSupport.INHERITABLE_THREAD_LOCALS_FIELD == null) {
            log.warn("[{}] ThreadLocal cleanup not available (JDK {} requires --add-opens)", lingId, JvmCleanupSupport.JDK_VERSION);
            return;
        }

        int cleaned = 0;
        int scannedThreads = 0;
        for (Thread t : JvmCleanupSupport.getActiveThreads()) {
            if (t == null)
                continue;

            // Java 21+: 虚拟线程的 ThreadLocal 存储方式不同，跳过
            if (JvmCleanupSupport.isVirtualThread(t))
                continue;

            scannedThreads++;
            cleaned += clearThreadLocalMap(lingId, t, JvmCleanupSupport.THREAD_LOCALS_FIELD, classLoader);
            cleaned += clearThreadLocalMap(lingId, t, JvmCleanupSupport.INHERITABLE_THREAD_LOCALS_FIELD, classLoader);
        }

        if (cleaned > 0) {
            log.info("[{}] Cleared {} ThreadLocal entries", lingId, cleaned);
        } else {
            log.debug("[{}] ThreadLocal scan finished, no entries removed (threads={})", lingId, scannedThreads);
        }
    }

    private int clearThreadLocalMap(String lingId, Thread t, Field mapField, ClassLoader cl) {
        if (mapField == null || JvmCleanupSupport.TLM_TABLE_FIELD == null)
            return 0;

        int cleaned = 0;
        try {
            Object map = mapField.get(t);
            if (map == null)
                return 0;

            Object[] table = (Object[]) JvmCleanupSupport.TLM_TABLE_FIELD.get(map);
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

                if (JvmCleanupSupport.isClassLoaderRelated(key, cl) || JvmCleanupSupport.isClassLoaderRelated(val, cl)
                        || JvmCleanupSupport.deepReferencesClassLoader(key, cl, 3)
                        || JvmCleanupSupport.deepReferencesClassLoader(val, cl, 3)) {
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
}
