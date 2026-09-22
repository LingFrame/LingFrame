package com.lingframe.core.resource;

import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shutdown Hook 跟踪器：主动跟踪灵元注册的 ShutdownHook。
 * <p>
 * 替代被动反射扫描 ApplicationShutdownHooks.hooks 的方式，
 * 通过维护灵元级 Hook 注册表，卸载时精确移除。
 * <p>
 * 使用方式：
 * <pre>
 * // 灵元代码注册 Hook 时（或通过字节码增强拦截 Runtime.addShutdownHook）
 * TrackedShutdownHooks.register(hook, lingClassLoader);
 *
 * // 卸载时
 * TrackedShutdownHooks.cleanupFor(lingClassLoader);
 * </pre>
 * <p>
 * 注意：由于无法拦截 Runtime.getRuntime().addShutdownHook() 的调用，
 * 此类作为补充机制，与 JvmShutdownHookUnloadHook 的反射扫描配合使用。
 * 灵元代码应通过 LingContext.registerShutdownHook() 注册，由框架统一跟踪。
 */
@Slf4j
public final class TrackedShutdownHooks {

    /** 灵元注册的 Hook 跟踪表：ClassLoader -> Set<Thread> */
    private static final Map<ClassLoader, Set<Thread>> TRACKED_HOOKS = new ConcurrentHashMap<>();

    private TrackedShutdownHooks() {
    }

    /**
     * 注册灵元 Shutdown Hook。
     * <p>
     * 同时调用 Runtime.addShutdownHook() 和跟踪表登记。
     *
     * @param hook            ShutdownHook 线程
     * @param lingClassLoader 灵元 ClassLoader
     */
    public static void register(Thread hook, ClassLoader lingClassLoader) {
        if (hook == null || lingClassLoader == null) return;
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            TRACKED_HOOKS.computeIfAbsent(lingClassLoader, cl -> ConcurrentHashMap.newKeySet()).add(hook);
            log.debug("Tracked shutdown hook registered: {} for CL: {}",
                    hook.getName(), lingClassLoader.getClass().getSimpleName());
        } catch (IllegalStateException e) {
            // JVM 正在退出，无法注册
            log.debug("Cannot register shutdown hook during JVM shutdown");
        } catch (Exception e) {
            log.debug("Failed to register tracked shutdown hook: {}", e.getMessage());
        }
    }

    /**
     * 清理指定 ClassLoader 关联的所有 Shutdown Hook。
     *
     * @param lingClassLoader 灵元 ClassLoader
     * @return 移除的 Hook 数量
     */
    public static int cleanupFor(ClassLoader lingClassLoader) {
        if (lingClassLoader == null) return 0;
        Set<Thread> hooks = TRACKED_HOOKS.remove(lingClassLoader);
        if (hooks == null || hooks.isEmpty()) return 0;

        int removed = 0;
        Iterator<Thread> it = hooks.iterator();
        while (it.hasNext()) {
            Thread hook = it.next();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
                removed++;
                log.info("Removed tracked shutdown hook: {}", hook.getName());
            } catch (IllegalStateException e) {
                // JVM 正在退出
                log.debug("Cannot remove shutdown hook during JVM shutdown");
            } catch (Exception e) {
                log.debug("Failed to remove tracked shutdown hook: {}", e.getMessage());
            }
            it.remove();
        }
        return removed;
    }

    /**
     * 获取指定 ClassLoader 关联的所有 Hook（用于诊断）。
     */
    public static Set<Thread> getTrackedHooks(ClassLoader lingClassLoader) {
        return TRACKED_HOOKS.get(lingClassLoader);
    }

    /**
     * 判断指定 ClassLoader 是否有跟踪的 Hook。
     */
    public static boolean hasTrackedHooks(ClassLoader lingClassLoader) {
        Set<Thread> hooks = TRACKED_HOOKS.get(lingClassLoader);
        return hooks != null && !hooks.isEmpty();
    }
}
