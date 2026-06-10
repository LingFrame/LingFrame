package com.lingframe.core.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一的命名线程工厂。
 * <p>
 * 为框架内所有线程池提供一致的命名规范、daemon 设置和 ClassLoader 绑定，
 * 便于线程 dump 排查和资源回收。
 * <p>
 * 命名格式：{@code prefix-1}, {@code prefix-2}, ...
 */
public class NamedThreadFactory implements ThreadFactory {

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
}
