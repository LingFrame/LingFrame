package com.lingframe.core.util;

import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一的命名线程工厂。
 * <p>
 * 为框架内所有线程池提供一致的命名规范、daemon 设置和 ClassLoader 绑定，
 * 便于线程 dump 排查和资源回收。
 * <p>
 * 命名格式：{@code prefix-1}, {@code prefix-2}, ...
 * <p>
 * 增强特性：维护灵元级线程跟踪表，卸载时可精确枚举灵元创建的所有线程进行清理。
 */
@Slf4j
public class NamedThreadFactory implements ThreadFactory {

    /** 灵元级线程跟踪表：ClassLoader -> Set<Thread> */
    private static final ConcurrentHashMap<ClassLoader, Set<Thread>> TRACKED_THREADS =
            new ConcurrentHashMap<>();

    private final AtomicInteger counter = new AtomicInteger(0);
    private final String prefix;
    private final boolean daemon;
    private final ClassLoader contextClassLoader;

    private NamedThreadFactory(String prefix, boolean daemon, ClassLoader contextClassLoader) {
        this.prefix = prefix;
        this.daemon = daemon;
        this.contextClassLoader = contextClassLoader;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
        thread.setDaemon(daemon);
        if (contextClassLoader != null) {
            thread.setContextClassLoader(contextClassLoader);
            // 登记到灵元线程跟踪表
            TRACKED_THREADS.computeIfAbsent(contextClassLoader, cl -> ConcurrentHashMap.newKeySet()).add(thread);
        }
        return thread;
    }

    /**
     * 创建 daemon 线程工厂，使用调用者的 ClassLoader
     */
    public static NamedThreadFactory daemon(String prefix) {
        return new NamedThreadFactory(prefix, true, null);
    }

    /**
     * 创建 daemon 线程工厂，绑定指定 ClassLoader
     */
    public static NamedThreadFactory daemon(String prefix, ClassLoader contextClassLoader) {
        return new NamedThreadFactory(prefix, true, contextClassLoader);
    }

    /**
     * 创建非 daemon 线程工厂
     */
    public static NamedThreadFactory nonDaemon(String prefix) {
        return new NamedThreadFactory(prefix, false, null);
    }

    // =========================================================================
    // 灵元线程跟踪与清理
    // =========================================================================

    /**
     * 获取指定 ClassLoader 关联的所有跟踪线程（用于诊断）。
     *
     * @param classLoader 灵元 ClassLoader
     * @return 跟踪的线程集合，可能为空
     */
    public static Set<Thread> getTrackedThreads(ClassLoader classLoader) {
        return TRACKED_THREADS.get(classLoader);
    }

    /**
     * 中断并清理指定 ClassLoader 关联的所有跟踪线程。
     * <p>
     * 卸载灵元时调用，精确清理框架创建的线程（而非全 JVM 扫描）。
     *
     * @param lingId        灵元 ID（用于日志）
     * @param classLoader   灵元 ClassLoader
     * @return 清理的线程数量
     */
    public static int cleanupThreads(String lingId, ClassLoader classLoader) {
        if (classLoader == null) return 0;
        Set<Thread> threads = TRACKED_THREADS.remove(classLoader);
        if (threads == null || threads.isEmpty()) return 0;

        int count = 0;
        Iterator<Thread> it = threads.iterator();
        while (it.hasNext()) {
            Thread t = it.next();
            try {
                if (t.isAlive() && !t.isDaemon()) {
                    log.info("[{}] Interrupting tracked thread: {} (state={})",
                            lingId, t.getName(), t.getState());
                    t.interrupt();
                    t.join(1000);
                }
                // 清理 contextClassLoader 引用
                if (t.getContextClassLoader() == classLoader) {
                    t.setContextClassLoader(ClassLoader.getSystemClassLoader());
                }
                count++;
            } catch (Exception e) {
                log.debug("[{}] Failed to cleanup tracked thread {}: {}",
                        lingId, t.getName(), e.getMessage());
            }
            it.remove();
        }
        if (count > 0) {
            log.info("[{}] Cleaned up {} tracked thread(s)", lingId, count);
        }
        return count;
    }

    /**
     * 判断指定 ClassLoader 是否有跟踪的线程。
     */
    public static boolean hasTrackedThreads(ClassLoader classLoader) {
        Set<Thread> threads = TRACKED_THREADS.get(classLoader);
        return threads != null && !threads.isEmpty();
    }
}
