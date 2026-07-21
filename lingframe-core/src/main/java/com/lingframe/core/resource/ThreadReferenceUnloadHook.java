package com.lingframe.core.resource;

import com.lingframe.core.spi.LingUnloadHook;
import com.lingframe.core.util.NamedThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.ref.Reference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 线程引用卸载钩子。
 * <p>
 * 清理目标 ClassLoader 关联的线程引用、MySQL 清理线程、H2 OnExitDatabaseCloser、
 * Timer 线程、遗留线程池、ThreadLocal 条目。
 * 这是最重的清理动作，涵盖：
 * <ul>
 *   <li>可疑线程诊断</li>
 *   <li>MySQL AbandonedConnectionCleanupThread 关闭</li>
 *   <li>H2 OnExitDatabaseCloser shutdown hook 移除</li>
 *   <li>java.util.Timer 线程中断</li>
 *   <li>遗留线程池（非 Bean）shutdownNow</li>
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

        // 每个步骤独立 try-catch：单步失败不得中断后续清理，否则会出现"日志突然消失"的现象
        // （历史上 invokeHook 只捕获 Exception，Error 会被 invokeAll 静默吞进 Future）

        // 诊断：打印可疑线程
        try {
            diagnoseSuspectThreads(lingId, classLoader);
        } catch (Throwable t) {
            log.warn("[{}] diagnoseSuspectThreads failed", lingId, t);
        }

        // 0. 精确清理框架跟踪的线程（NamedThreadFactory 创建的线程）
        try {
            NamedThreadFactory.cleanupThreads(lingId, classLoader);
        } catch (Throwable t) {
            log.warn("[{}] NamedThreadFactory.cleanupThreads failed", lingId, t);
        }

        // 1. MySQL 清理线程
        try {
            cleanupMySqlThread(lingId, classLoader);
        } catch (Throwable t) {
            log.warn("[{}] cleanupMySqlThread failed", lingId, t);
        }

        // 2. H2 OnExitDatabaseCloser 清理
        try {
            cleanupH2OnExitCloser(lingId, classLoader);
        } catch (Throwable t) {
            log.warn("[{}] cleanupH2OnExitCloser failed", lingId, t);
        }

        // 3. Timer 线程清理
        try {
            stopTimerThreads(lingId, classLoader);
        } catch (Throwable t) {
            log.warn("[{}] stopTimerThreads failed", lingId, t);
        }

        // 3.5 HttpClient SelectorManager 守护线程清理
        // Spring Boot 3 / JDK 11+ 的 jdk.internal.net.http.HttpClientImpl 启动后
        // 会留下一个 SelectorManager 守护线程（线程名形如 "HttpClient-<id>-SelectorManager"），
        // 其 contextClassLoader 持有灵元 ClassLoader，阻止灵元 CL 被 GC 回收。
        // 该线程在灵元 undeploy 链路里不会被自动终止，需要显式中断 + 置空 contextCL。
        // Spring Boot 2 / JDK 8 下灵元若未使用 HttpClient 则无此线程，扫描为 no-op。
        try {
            stopHttpClientSelectorThreads(lingId, classLoader);
        } catch (Throwable t) {
            log.warn("[{}] stopHttpClientSelectorThreads failed", lingId, t);
        }

        // 4. 遗留线程池清理
        try {
            shutdownOrphanThreadPools(lingId, classLoader);
        } catch (Throwable t) {
            log.warn("[{}] shutdownOrphanThreadPools failed", lingId, t);
        }

        // 5. 线程引用清理
        try {
            clearThreadReferences(lingId, classLoader);
        } catch (Throwable t) {
            log.warn("[{}] clearThreadReferences failed", lingId, t);
        }

        // 6. ThreadLocal 清理
        try {
            clearThreadLocals(lingId, classLoader);
        } catch (Throwable t) {
            log.warn("[{}] clearThreadLocals failed", lingId, t);
        }

        // 7. 清理跟踪的 Shutdown Hook（TrackedShutdownHooks）
        try {
            int removedHooks = TrackedShutdownHooks.cleanupFor(classLoader);
            if (removedHooks > 0) {
                log.info("[{}] Removed {} tracked shutdown hook(s)", lingId, removedHooks);
            }
        } catch (Throwable t) {
            log.warn("[{}] TrackedShutdownHooks.cleanupFor failed", lingId, t);
        }

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
    // H2 OnExitDatabaseCloser 清理
    // =========================================================================

    /**
     * 清理 H2 的 OnExitDatabaseCloser shutdown hook。
     * <p>
     * H2 在打开数据库时通过 Runtime.addShutdownHook() 注册 OnExitDatabaseCloser，
     * 但 DataSource.close() 不会触发 Database.close()（Database 实例共享），
     * 导致 shutdown hook 残留并持有灵元 ClassLoader 引用。
     * <p>
     * 清理策略：
     * 1. 扫描 ApplicationShutdownHooks.hooks，移除关联目标 CL 的 OnExitDatabaseCloser
     * 2. 反射调用 stopClosing() 方法（H2 2.x）
     * 3. 兜底：扫描活动线程，清理 OnExitDatabaseCloser 的 contextClassLoader
     */
    private void cleanupH2OnExitCloser(String lingId, ClassLoader classLoader) {
        log.info("[{}] Looking for H2 OnExitDatabaseCloser...", lingId);
        int removed = 0;

        // 策略 1：从 ApplicationShutdownHooks 中移除
        try {
            Class<?> hooksClass = Class.forName("java.lang.ApplicationShutdownHooks");
            Field hooksField = hooksClass.getDeclaredField("hooks");
            hooksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Thread, Thread> hooks = (Map<Thread, Thread>) hooksField.get(null);
            if (hooks != null) {
                List<Thread> toRemove = new ArrayList<>();
                for (Thread hook : hooks.keySet()) {
                    if (hook == null) continue;
                    if (!isH2OnExitCloser(hook)) continue;
                    // 匹配规则：contextClassLoader 或类加载器关联目标 CL
                    if (hook.getContextClassLoader() == classLoader
                            || isLoadedBy(hook.getClass().getClassLoader(), classLoader)) {
                        toRemove.add(hook);
                    }
                }
                for (Thread hook : toRemove) {
                    try {
                        // 先调用 stopClosing() 让 H2 内部状态一致
                        invokeStopClosing(hook);
                        Runtime.getRuntime().removeShutdownHook(hook);
                        log.info("[{}] Removed H2 OnExitDatabaseCloser: {}", lingId, hook.getName());
                        removed++;
                    } catch (IllegalStateException e) {
                        // JVM 正在退出
                        log.debug("[{}] Cannot remove H2 hook during JVM shutdown", lingId);
                    } catch (Exception e) {
                        log.debug("[{}] Failed to remove H2 hook: {}", lingId, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[{}] H2 shutdown hook scan failed: {}", lingId, e.getMessage());
        }

        // 策略 2：扫描活动线程，清理 OnExitDatabaseCloser 的 contextClassLoader
        for (Thread t : JvmCleanupSupport.getActiveThreads()) {
            if (t == null) continue;
            if (!isH2OnExitCloser(t)) continue;
            try {
                if (t.getContextClassLoader() == classLoader) {
                    t.setContextClassLoader(ClassLoader.getSystemClassLoader());
                    invokeStopClosing(t);
                    log.info("[{}] Cleared contextClassLoader on H2 OnExitDatabaseCloser: {}", lingId, t.getName());
                    removed++;
                }
            } catch (Exception e) {
                log.debug("[{}] Failed to clean H2 thread: {}", lingId, e.getMessage());
            }
        }

        if (removed == 0) {
            log.info("[{}] No H2 OnExitDatabaseCloser found", lingId);
        } else {
            log.info("[{}] H2 OnExitDatabaseCloser cleanup complete, removed: {}", lingId, removed);
        }
    }

    /** 判断线程是否为 H2 OnExitDatabaseCloser */
    private boolean isH2OnExitCloser(Thread t) {
        String className = t.getClass().getName();
        return className.equals("org.h2.engine.OnExitDatabaseCloser")
                || className.contains("OnExitDatabaseCloser");
    }

    /** 反射调用 H2 OnExitDatabaseCloser.stopClosing() */
    private void invokeStopClosing(Thread hook) {
        try {
            Method stopMethod = hook.getClass().getDeclaredMethod("stopClosing");
            stopMethod.setAccessible(true);
            stopMethod.invoke(hook);
        } catch (NoSuchMethodException e) {
            // H2 版本不同，方法可能不存在
        } catch (Exception e) {
            log.debug("Failed to invoke stopClosing: {}", e.getMessage());
        }
    }

    // =========================================================================
    // Timer 线程清理
    // =========================================================================

    /**
     * 中断由目标 ClassLoader 创建的 java.util.Timer 线程。
     * <p>
     * Timer 线程（TimerThread）是非守护线程，会阻止 JVM 退出并持有 ClassLoader 引用。
     * 灵元代码若通过 new Timer() 创建定时任务，卸载时必须中断。
     */
    private void stopTimerThreads(String lingId, ClassLoader classLoader) {
        int count = 0;
        for (Thread t : JvmCleanupSupport.getActiveThreads()) {
            if (t == null) continue;
            if (JvmCleanupSupport.isVirtualThread(t)) continue;

            String typeName = t.getClass().getName();
            if (!typeName.equals("java.util.TimerThread")) continue;

            // 匹配：线程类由目标 CL 加载，或 contextClassLoader 关联目标 CL
            if (t.getClass().getClassLoader() == classLoader
                    || t.getContextClassLoader() == classLoader) {
                log.info("[{}] Interrupting Timer thread: {} (state={})",
                        lingId, t.getName(), t.getState());
                t.interrupt();
                try {
                    t.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                count++;
            }
        }
        if (count > 0) {
            log.info("[{}] Stopped {} Timer thread(s)", lingId, count);
        }
    }

    /**
     * 终止 jdk.internal.net.http.HttpClientImpl 的 SelectorManager 守护线程。
     * <p>
     * JDK 11+ 的 HttpClient 启动后会留下一个 SelectorManager 守护线程
     * （线程名形如 "HttpClient-<id>-SelectorManager"），其 contextClassLoader
     * 持有灵元 ClassLoader，阻止灵元 CL 被 GC 回收。
     * <p>
     * 该线程在中断后会自行退出 selector 阻塞循环（HttpClient 内部 awaiter 队列为空时收尾），
     * 若中段后仍存活则显式置空 contextClassLoader，断开对灵元 CL 的强引用。
     * JDK 8 / 灵元未使用 HttpClient 时扫描为 no-op。
     */
    private void stopHttpClientSelectorThreads(String lingId, ClassLoader classLoader) {
        int count = 0;
        for (Thread t : JvmCleanupSupport.getActiveThreads()) {
            if (t == null) continue;
            if (JvmCleanupSupport.isVirtualThread(t)) continue;

            String name = t.getName();
            // HttpClient 守护线程名形如 "HttpClient-2-SelectorManager"
            if (!name.startsWith("HttpClient-") || !name.contains("SelectorManager")) {
                continue;
            }
            ClassLoader tccl = JvmCleanupSupport.getContextClassLoaderSafe(t);
            if (tccl != classLoader) {
                // 仅处理关联灵元 CL 的线程，避免误杀其他灵元或灵核 HttpClient
                continue;
            }

            log.info("[{}] Interrupting HttpClient SelectorManager thread: {} (state={})",
                    lingId, name, t.getState());
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (t.isAlive()) {
                // 中断后仍存活：HttpClient 内部 selector 可能处于长阻塞，
                // 显式置空 contextClassLoader 断开强引用，允许灵元 CL 被 GC
                try {
                    t.setContextClassLoader(null);
                    log.info("[{}] HttpClient SelectorManager {} still alive after interrupt, cleared contextCL",
                            lingId, name);
                } catch (SecurityException ignored) {
                    // 受 SecurityManager 限制无法置空，记录但仍计入处理数
                    log.debug("[{}] Cannot clear contextCL on thread {}", lingId, name);
                }
            }
            count++;
        }
        if (count > 0) {
            log.info("[{}] Stopped {} HttpClient SelectorManager thread(s)", lingId, count);
        }
    }

    // =========================================================================
    // 遗留线程池清理
    // =========================================================================

    /**
     * 扫描所有活动线程，通过反射追溯到 ThreadPoolExecutor 并 shutdownNow()。
     * <p>
     * 覆盖灵元代码自建的非 Bean 线程池（如 Executors.newFixedThreadPool()）。
     * ExecutorCleaner 只能清理 BeanFactory 中的 ExecutorService Bean，
     * 此方法作为兜底，扫描线程的 target 字段找到 Worker → ThreadPoolExecutor。
     */
    private void shutdownOrphanThreadPools(String lingId, ClassLoader classLoader) {
        Set<ThreadPoolExecutor> pools = Collections.newSetFromMap(
                new IdentityHashMap<>());

        for (Thread t : JvmCleanupSupport.getActiveThreads()) {
            if (t == null) continue;
            if (JvmCleanupSupport.isVirtualThread(t)) continue;

            // 仅处理关联目标 CL 的线程
            if (t.getContextClassLoader() != classLoader
                    && t.getClass().getClassLoader() != classLoader) {
                continue;
            }

            // 通过反射追溯 target → Worker → outer ThreadPoolExecutor
            ThreadPoolExecutor pool = extractThreadPoolExecutor(t);
            if (pool != null) {
                pools.add(pool);
            }
        }

        for (ThreadPoolExecutor pool : pools) {
            try {
                log.info("[{}] Shutting down orphan ThreadPoolExecutor: {}", lingId, pool);
                List<Runnable> drained = pool.shutdownNow();
                if (!drained.isEmpty()) {
                    log.info("[{}] Drained {} pending task(s)", lingId, drained.size());
                }
                pool.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // 恢复中断标志，避免上层丢失中断信号
                Thread.currentThread().interrupt();
                log.debug("[{}] Interrupted while awaiting ThreadPoolExecutor termination", lingId);
            } catch (Exception e) {
                log.debug("[{}] Failed to shutdown ThreadPoolExecutor: {}", lingId, e.getMessage());
            }
        }
        if (!pools.isEmpty()) {
            log.info("[{}] Shutdown {} orphan ThreadPoolExecutor(s)", lingId, pools.size());
        }
    }

    /** 通过反射从线程的 target 字段追溯到 ThreadPoolExecutor */
    private ThreadPoolExecutor extractThreadPoolExecutor(Thread t) {
        try {
            Field targetField = Thread.class.getDeclaredField("target");
            targetField.setAccessible(true);
            Object target = targetField.get(t);
            if (target == null) return null;

            // ThreadPoolExecutor$Worker 持有 this$0 = ThreadPoolExecutor
            Class<?> targetClass = target.getClass();
            if (!targetClass.getName().contains("Worker")) return null;

            // 反射获取 this$0
            Field outerField = null;
            Class<?> clazz = targetClass;
            while (clazz != null && outerField == null) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (f.getName().equals("this$0")) {
                        outerField = f;
                        break;
                    }
                }
                clazz = clazz.getSuperclass();
            }
            if (outerField == null) return null;

            outerField.setAccessible(true);
            Object outer = outerField.get(target);
            if (outer instanceof ThreadPoolExecutor) {
                return (ThreadPoolExecutor) outer;
            }
        } catch (Exception e) {
            log.debug("Failed to extract ThreadPoolExecutor from thread {}: {}", t.getName(), e.getMessage());
        }
        return null;
    }

    /** 判断 ClassLoader cl 是否由 target 加载（或就是 target） */
    private boolean isLoadedBy(ClassLoader cl, ClassLoader target) {
        while (cl != null) {
            if (cl == target) return true;
            cl = cl.getParent();
        }
        return false;
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
        int leaked = 0;
        int scannedThreads = 0;
        Thread currentThread = Thread.currentThread();
        for (Thread t : JvmCleanupSupport.getActiveThreads()) {
            if (t == null)
                continue;

            // Java 21+: 虚拟线程的 ThreadLocal 存储方式不同，跳过
            if (JvmCleanupSupport.isVirtualThread(t))
                continue;

            scannedThreads++;
            boolean isCurrent = (t == currentThread);
            int[] r1 = scanThreadLocalMap(lingId, t, JvmCleanupSupport.THREAD_LOCALS_FIELD, classLoader, isCurrent);
            int[] r2 = scanThreadLocalMap(lingId, t, JvmCleanupSupport.INHERITABLE_THREAD_LOCALS_FIELD, classLoader, isCurrent);
            cleaned += r1[0] + r2[0];
            leaked += r1[1] + r2[1];
        }

        if (cleaned > 0 || leaked > 0) {
            log.info("[{}] ThreadLocal cleanup: removed={}, leaked={}", lingId, cleaned, leaked);
        } else {
            log.debug("[{}] ThreadLocal scan finished, no entries removed (threads={})", lingId, scannedThreads);
        }
    }

    /**
     * 扫描单个 ThreadLocalMap，检测并处理关联目标 CL 的条目。
     * <p>
     * 处理策略：
     * <ul>
     *   <li>当前线程(卸载线程): 调用 {@link ThreadLocal#remove()} 公开 API 安全清理</li>
     *   <li>跨线程: 仅记录泄漏警告，不强制侵入清理</li>
     * </ul>
     * <p>
     * <b>禁止</b>反射修改 Entry 内部数据(valueField.set(entry, null) 和 ref.clear())——
     * 并发 get() 会在窗口期返回 null 致 NPE。
     *
     * @return int[2] 数组：[0]=已清理数，[1]=泄漏记录数
     */
    private int[] scanThreadLocalMap(String lingId, Thread t, Field mapField, ClassLoader cl, boolean isCurrent) {
        if (mapField == null || JvmCleanupSupport.TLM_TABLE_FIELD == null)
            return new int[]{0, 0};

        int cleaned = 0;
        int leaked = 0;
        try {
            Object map = mapField.get(t);
            if (map == null)
                return new int[]{0, 0};

            Object[] table = (Object[]) JvmCleanupSupport.TLM_TABLE_FIELD.get(map);
            if (table == null)
                return new int[]{0, 0};

            List<String> samples = new ArrayList<>();
            int logged = 0;

            for (Object entry : table) {
                if (entry == null)
                    continue;

                String sample = inspectThreadLocalEntry(entry, cl);
                if (sample == null)
                    continue;

                // 检测到关联灵元 CL 的 ThreadLocal 条目
                if (isCurrent) {
                    // 当前线程: 通过 ThreadLocal.remove() 公开 API 安全清理
                    if (removeEntryViaThreadLocalApi(entry)) {
                        cleaned++;
                        if (logged < 3) {
                            samples.add(sample + " (cleaned)");
                            logged++;
                        }
                    } else {
                        // key 不是 ThreadLocal 实例(如 InheritableThreadLocal 跨线程传递的)，
                        // 仍记为泄漏
                        leaked++;
                        if (logged < 3) {
                            samples.add(sample + " (leaked: non-ThreadLocal key)");
                            logged++;
                        }
                    }
                } else {
                    // 跨线程: 仅记录泄漏警告，不强制侵入清理
                    // 设计决策：跨线程 ThreadLocal 反射清理不安全（可能破坏目标线程 ThreadLocalMap 结构），
                    // 反射修改 Entry 内部数据（valueField.set(entry,null) + ref.clear()）会导致并发 get()
                    // 返回 null 触发 NPE，且可能破坏目标线程 ThreadLocalMap 的槽位/size 一致性。
                    // 框架仅告警不清理；灵元应在卸载前自行 remove 自身注册的 ThreadLocal。
                    leaked++;
                    if (logged < 3) {
                        samples.add(sample + " (leaked: cross-thread)");
                        logged++;
                    }
                }
            }

            if (!samples.isEmpty()) {
                if (isCurrent) {
                    log.debug("[{}] ThreadLocal hits on current thread {}: {}", lingId, t.getName(), samples);
                } else {
                    log.warn("[{}] ThreadLocal leak on thread {}: {}", lingId, t.getName(), samples);
                }
            }
        } catch (Exception e) {
            log.trace("Failed to scan ThreadLocal on thread {}: {}", t.getName(), e.getMessage());
        }
        return new int[]{cleaned, leaked};
    }

    /**
     * 检查单个 ThreadLocal entry 是否与目标 ClassLoader 关联。
     * <p>
     * 仅做检测，不做任何修改。保留泄漏检测能力。
     * <p>
     * 使用 {@link JvmCleanupSupport#TLM_ENTRY_VALUE_FIELD} 启动期缓存的反射字段，
     * 避免热路径逐条目 getDeclaredField("value")+setAccessible 的冗余反射开销。
     * Entry 类型固定为 {@code java.lang.ThreadLocal$ThreadLocalMap$Entry}。
     *
     * @return 关联时返回 entry 描述(用于日志)，不关联或异常时返回 null
     */
    private String inspectThreadLocalEntry(Object entry, ClassLoader cl) {
        if (JvmCleanupSupport.TLM_ENTRY_VALUE_FIELD == null) {
            return null;
        }
        try {
            Reference<?> ref = (Reference<?>) entry;
            Object key = ref.get();
            // 取 value 字段用于检测和描述（使用启动期缓存的反射字段）
            Object val = JvmCleanupSupport.TLM_ENTRY_VALUE_FIELD.get(entry);

            if (JvmCleanupSupport.isClassLoaderRelated(key, cl) || JvmCleanupSupport.isClassLoaderRelated(val, cl)
                    || JvmCleanupSupport.deepReferencesClassLoader(key, cl, 3)
                    || JvmCleanupSupport.deepReferencesClassLoader(val, cl, 3)) {
                return describeThreadLocalEntry(key, val);
            }
        } catch (Exception e) {
            log.trace("Failed to inspect a single ThreadLocal entry: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 通过 {@link ThreadLocal#remove()} 公开 API 安全移除当前线程的 ThreadLocal 条目。
     * <p>
     * <b>禁止</b>反射修改 Entry 内部数据(valueField.set(entry, null) 和 ref.clear())——
     * 并发 get() 会在窗口期返回 null 致 NPE。remove() 是 JDK 公开 API，内部自带同步和 expunge 逻辑。
     *
     * @return 成功移除返回 true，key 不是 ThreadLocal 或调用失败返回 false
     */
    private boolean removeEntryViaThreadLocalApi(Object entry) {
        try {
            Reference<?> ref = (Reference<?>) entry;
            Object key = ref.get();
            if (key instanceof ThreadLocal) {
                ((ThreadLocal<?>) key).remove();
                return true;
            }
        } catch (Exception e) {
            log.trace("Failed to remove ThreadLocal entry via remove(): {}", e.getMessage());
        }
        return false;
    }

    private String describeThreadLocalEntry(Object key, Object val) {
        String keyType = key == null ? "null" : key.getClass().getName();
        String valType = val == null ? "null" : val.getClass().getName();
        return "key=" + keyType + ", val=" + valType;
    }
}
