package com.lingframe.core.resource;

import com.lingframe.core.spi.LingUnloadHook;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JVM ShutdownHook 卸载钩子。
 * <p>
 * 清理由目标 ClassLoader 注册的所有 ShutdownHook，防止灵元卸载后残留 Hook 阻止 JVM 退出。
 * <p>
 * 匹配规则（二选一）：
 * <ul>
 *   <li>Hook 线程的 contextClassLoader 与目标 ClassLoader 相同</li>
 *   <li>Hook 线程的 Class（即 Runnable 的实际类）由目标 ClassLoader 加载</li>
 * </ul>
 * 第二条规则覆盖灵元自定义 Hook 名的场景——只要 Hook 类由灵元 ClassLoader 加载，
 * 无论线程名叫什么，都会被清理。
 * <p>
 * 移除 Hook 时同时清理 Thread 的 contextClassLoader，防止 Thread 对象残留导致
 * ClassLoader 泄漏（如 H2 OnExitDatabaseCloser）。
 */
@Slf4j
public class JvmShutdownHookUnloadHook implements LingUnloadHook {

    @Override
    public void cleanup(String lingId, ClassLoader classLoader) {
        if (!JvmCleanupSupport.isSafeToCleanup(lingId, classLoader)) {
            return;
        }
        int hookCount = clearShutdownHooks(lingId, classLoader);
        if (hookCount > 0) {
            log.info("[{}] Removed {} shutdown hook(s)", lingId, hookCount);
        }
    }

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
            // 关键同步：ApplicationShutdownHooks 内部用 synchronized(hooksClass) 保护 hooks Map，
            // 这里必须以相同锁同步快照，否则并发 addShutdownHook/removeShutdownHook 会触发 CME
            List<Thread> toRemove = new ArrayList<>();
            synchronized (hooksClass) {
                for (Thread hook : hooks.keySet()) {
                    if (hook == null) {
                        continue;
                    }
                    // 规则 1：Hook 线程的 contextClassLoader 属于目标灵元
                    ClassLoader tccl = hook.getContextClassLoader();
                    if (tccl == classLoader) {
                        toRemove.add(hook);
                        continue;
                    }
                    // 规则 2：Hook 的 Runnable 类由目标灵元 ClassLoader 加载
                    // 覆盖灵元自定义 Hook 名的场景（如 "my-cleanup-hook"）
                    ClassLoader hookClassCL = hook.getClass().getClassLoader();
                    if (hookClassCL == classLoader) {
                        toRemove.add(hook);
                    }
                }
            }
            for (Thread hook : toRemove) {
                try {
                    // 先清理 contextClassLoader，防止 Thread 对象残留导致 ClassLoader 泄漏
                    // 关键：removeShutdownHook 只从 hooks Map 中移除，Thread 对象可能被其他地方引用
                    // （如 H2 OnExitDatabaseCloser 被其内部静态字段缓存）
                    if (hook.getContextClassLoader() == classLoader) {
                        hook.setContextClassLoader(ClassLoader.getSystemClassLoader());
                    }
                    // 用公开 API 移除，避免反射直接改 hooks Map 引发状态不一致
                    Runtime.getRuntime().removeShutdownHook(hook);
                    log.info("[{}] Removed shutdown hook: {} (class={})",
                            lingId, hook.getName(), hook.getClass().getName());
                } catch (IllegalStateException e) {
                    // JVM 正在退出，无法移除，但仍需清理 contextClassLoader
                    clearContextClassLoader(hook, classLoader);
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

    /** 安全清理 Thread 的 contextClassLoader */
    private void clearContextClassLoader(Thread hook, ClassLoader classLoader) {
        try {
            if (hook.getContextClassLoader() == classLoader) {
                hook.setContextClassLoader(ClassLoader.getSystemClassLoader());
            }
        } catch (Exception e) {
            log.debug("Failed to clear contextClassLoader on {}: {}", hook.getName(), e.getMessage());
        }
    }
}
