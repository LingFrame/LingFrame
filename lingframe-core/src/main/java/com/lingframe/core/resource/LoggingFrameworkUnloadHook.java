package com.lingframe.core.resource;

import com.lingframe.core.spi.LingUnloadHook;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * 日志框架引用卸载钩子。
 * <p>
 * 主动清理 logback、log4j2、java.util.logging 中关联目标 ClassLoader 的引用。
 * <p>
 * <b>核心安全约束</b>：日志框架门面与实现通常委派给父加载器，灵元共享灵核实例——
 * 但委派来源分两层：
 * <ul>
 *   <li>{@code LingClassLoader.FORCE_PARENT_PACKAGES}（core）：仅 slf4j 门面，属灵珑自身依赖</li>
 *   <li>适配层 {@code addParentDelegatePackages}（runtime）：logback/log4j2 等实现，属灵核生态环境</li>
 * </ul>
 * 因此<b>必须严格判定日志框架实例的 ClassLoader</b>，
 * 只清理灵元 CL 自己加载的日志框架实例，绝不能 shutdown/reset 全局实例，
 * 否则会导致整个 JVM 的日志全部失效（后续所有 log 不再输出且不报错）
 * <p>
 * 清理项（仅当日志框架类由灵元 CL 加载时才执行）：
 * <ul>
 *   <li>logback: LoggerContext.shutdown()，清理 LoggerRepository</li>
 *   <li>log4j2: LogManager.shutdown()，清理 LoggerContext</li>
 *   <li>java.util.logging: LogManager 永远由 bootstrap CL 加载，仅清理灵元注册的 Logger</li>
 *   <li>slf4j: 反射清理 ILoggerFactory 内部 Map 中灵元 CL 加载的 logger 条目，
 *       不清空 LoggerFactory 静态字段（避免并发 getLogger() NPE）</li>
 * </ul>
 */
@Slf4j
public class LoggingFrameworkUnloadHook implements LingUnloadHook {

    @Override
    public void cleanup(String lingId, ClassLoader classLoader) {
        if (!JvmCleanupSupport.isSafeToCleanup(lingId, classLoader)) {
            return;
        }
        log.info("[{}] Starting logging framework cleanup...", lingId);

        cleanupLogback(lingId, classLoader);
        cleanupLog4j2(lingId, classLoader);
        cleanupJul(lingId, classLoader);
        cleanupSlf4j(lingId, classLoader);

        log.info("[{}] Logging framework cleanup completed", lingId);
    }

    // =========================================================================
    // logback 清理
    // =========================================================================

    /**
     * 清理 logback LoggerContext。
     * <p>
     * <b>安全判定</b>：只当 LoggerContext 实例由灵元 CL 加载时才 shutdown/reset。
     * 若由父 CL 加载（通常情况），说明灵元共享灵核 logback，必须跳过，
     * 否则会关闭整个 JVM 的日志输出。
     */
    @SuppressWarnings("unchecked")
    private void cleanupLogback(String lingId, ClassLoader classLoader) {
        try {
            Class<?> loggerFactoryClass = Class.forName("org.slf4j.LoggerFactory");
            Method getFactoryMethod = loggerFactoryClass.getMethod("getILoggerFactory");
            Object factory = getFactoryMethod.invoke(null);

            if (factory == null) return;

            // 关键安全判定：LoggerContext 是否由灵元 CL 加载
            // 若由父 CL 加载，灵元共享灵核 logback 实例，绝不能 shutdown，否则全 JVM 日志失效
            ClassLoader factoryCL = factory.getClass().getClassLoader();
            if (factoryCL != classLoader) {
                log.debug("[{}] logback LoggerContext loaded by non-ling CL ({}), skip to protect lingcore logging",
                        lingId, factoryCL);
                return;
            }

            String factoryClassName = factory.getClass().getName();
            if (factoryClassName.contains("LoggerContext")) {
                // 调用 shutdown() 方法
                try {
                    Method shutdownMethod = factory.getClass().getMethod("shutdown");
                    shutdownMethod.invoke(factory);
                    log.info("[{}] Shutdown logback LoggerContext", lingId);
                } catch (NoSuchMethodException e) {
                    // 老版本 logback，调用 reset()
                    try {
                        Method resetMethod = factory.getClass().getMethod("reset");
                        resetMethod.invoke(factory);
                        log.info("[{}] Reset logback LoggerContext", lingId);
                    } catch (NoSuchMethodException e2) {
                        log.debug("[{}] logback LoggerContext no shutdown/reset method", lingId);
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            // logback 不在 classpath
            log.debug("[{}] logback not available, skip", lingId);
        } catch (Exception e) {
            log.debug("[{}] logback cleanup failed: {}", lingId, e.getMessage());
        }
    }

    // =========================================================================
    // log4j2 清理
    // =========================================================================

    /**
     * 清理 log4j2 LogManager。
     * <p>
     * <b>安全判定</b>：只当 LogManager 类由灵元 CL 加载时才 shutdown。
     * 若由父 CL 加载，必须跳过，否则会关闭灵核 log4j2。
     */
    private void cleanupLog4j2(String lingId, ClassLoader classLoader) {
        try {
            Class<?> logManagerClass = Class.forName("org.apache.logging.log4j.LogManager");

            // 关键安全判定：LogManager 是否由灵元 CL 加载
            if (logManagerClass.getClassLoader() != classLoader) {
                log.debug("[{}] log4j2 LogManager loaded by non-ling CL ({}), skip to protect lingcore logging",
                        lingId, logManagerClass.getClassLoader());
                return;
            }

            try {
                Method shutdownMethod = logManagerClass.getMethod("shutdown");
                shutdownMethod.invoke(null);
                log.info("[{}] Shutdown log4j2 LogManager", lingId);
            } catch (NoSuchMethodException e) {
                // 旧版本 log4j2，尝试 shutdown(ctx)
                try {
                    Method getContextMethod = logManagerClass.getMethod("getContext", boolean.class);
                    Object ctx = getContextMethod.invoke(null, false);
                    if (ctx != null) {
                        Method shutdownMethod = ctx.getClass().getMethod("stop");
                        shutdownMethod.invoke(ctx);
                        log.info("[{}] Stopped log4j2 LoggerContext", lingId);
                    }
                } catch (NoSuchMethodException e2) {
                    log.debug("[{}] log4j2 no shutdown method available", lingId);
                }
            }
        } catch (ClassNotFoundException e) {
            log.debug("[{}] log4j2 not available, skip", lingId);
        } catch (Exception e) {
            log.debug("[{}] log4j2 cleanup failed: {}", lingId, e.getMessage());
        }
    }

    // =========================================================================
    // java.util.logging 清理
    // =========================================================================

    /**
     * 清理 java.util.logging LogManager。
     * <p>
     * <b>安全判定</b>：JUL 的 LogManager 永远由 bootstrap CL 加载（getClassLoader()=null），
     * 不可能由灵元 CL 加载。{@code LogManager.reset()} 会重置全 JVM 的所有 JUL Logger，
     * 包括灵核的，因此<b>禁止对全局 LogManager 调用 reset()</b>。
     * <p>
     * 这里仅清理灵元 CL 加载的 Logger（通过反射扫描 logger 名空间），
     * 若无灵元加载的 Logger 则跳过。
     */
    private void cleanupJul(String lingId, ClassLoader classLoader) {
        try {
            LogManager logManager = LogManager.getLogManager();

            // JUL LogManager 永远由 bootstrap CL 加载，不能 reset()，否则会关闭灵核所有 JUL 日志
            // 这里只清理灵元 CL 加载的 Logger（通过反射枚举 logger 名空间）
            int cleared = 0;
            try {
                Field loggersField = logManager.getClass().getDeclaredField("loggers");
                loggersField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, Logger> loggers =
                        (Map<String, Logger>) loggersField.get(logManager);
                if (loggers != null) {
                    // 与 slf4j 路径对齐：仅 ConcurrentHashMap 实例可安全并发 remove，
                    // 非 CHM（如 HashMap）在并发 getLogger 时 remove 会破坏内部结构，跳过 remove 仅清理 handlers
                    if (!(loggers instanceof ConcurrentHashMap)) {
                        log.warn("[{}] JUL LogManager.loggers is non-concurrent Map ({}), skip remove to avoid structural corruption",
                                lingId, loggers.getClass().getName());
                        for (String name : new ArrayList<>(loggers.keySet())) {
                            Logger logger = loggers.get(name);
                            if (logger != null && logger.getClass().getClassLoader() == classLoader) {
                                logger.setUseParentHandlers(false);
                                for (Handler h : logger.getHandlers()) {
                                    logger.removeHandler(h);
                                }
                                cleared++;
                            }
                        }
                    } else {
                        // CHM 可安全并发 remove：复制 key 集合后逐条清理
                        List<String> names = new ArrayList<>(loggers.keySet());
                        for (String name : names) {
                            Logger logger = loggers.get(name);
                            if (logger == null) continue;
                            // 只清理由灵元 CL 加载的 Logger
                            if (logger.getClass().getClassLoader() == classLoader) {
                                loggers.remove(name);
                                // 重置 logger 的 handlers，断开对灵元 CL 的引用
                                logger.setUseParentHandlers(false);
                                for (Handler h : logger.getHandlers()) {
                                    logger.removeHandler(h);
                                }
                                cleared++;
                            }
                        }
                    }
                }
            } catch (NoSuchFieldException e) {
                log.debug("[{}] JUL LogManager.loggers field not accessible, skip", lingId);
            } catch (Exception e) {
                log.debug("[{}] JUL logger scan failed: {}", lingId, e.getMessage());
            }

            if (cleared > 0) {
                log.info("[{}] Cleared {} JUL logger(s) loaded by ling CL", lingId, cleared);
            } else {
                log.debug("[{}] No JUL logger loaded by ling CL, skip", lingId);
            }
        } catch (Exception e) {
            log.debug("[{}] JUL cleanup failed: {}", lingId, e.getMessage());
        }
    }

    // =========================================================================
    // slf4j 清理
    // =========================================================================

    /**
     * 清理 slf4j LoggerFactory 的静态缓存。
     * <p>
     * <b>安全判定</b>：只当 ILoggerFactory 实例由灵元 CL 加载时才清理其内部 Map。
     * 若由父 CL 加载，必须跳过，否则会破坏灵核的 slf4j 绑定。
     * <p>
     * <b>关键约束</b>：
     * <ul>
     *   <li>禁止 {@code f.set(null, null)} 清空 LoggerFactory 的静态字段——
     *       会在并发 logger.getLogger() 调用窗口期触发 NPE（getLogger 内部未做 null 防护）</li>
     *   <li>禁止 {@code LoggerContext.reset()}——会清空全 JVM 的 logger，导致灵核日志失效</li>
     *   <li>仅反射清理 ILoggerFactory 内部 Map 中关联灵元 CL 的 logger 条目</li>
     * </ul>
     */
    private void cleanupSlf4j(String lingId, ClassLoader classLoader) {
        try {
            Class<?> loggerFactoryClass = Class.forName("org.slf4j.LoggerFactory");
            Method getFactoryMethod = loggerFactoryClass.getMethod("getILoggerFactory");
            Object factory = getFactoryMethod.invoke(null);
            if (factory == null) {
                return;
            }

            // 关键安全判定：ILoggerFactory 是否由灵元 CL 加载
            // 若由父 CL 加载（通常情况），灵元共享灵核 slf4j 绑定，绝不能清理其内部状态
            ClassLoader factoryCL = factory.getClass().getClassLoader();
            if (factoryCL != classLoader) {
                log.debug("[{}] slf4j ILoggerFactory loaded by non-ling CL ({}), skip to protect lingcore binding",
                        lingId, factoryCL);
                return;
            }

            // 反射清理 ILoggerFactory 内部 Map 中灵元 CL 加载的 logger 条目
            // 仅清理条目，不 reset() 也不清空静态字段，避免并发 getLogger() NPE
            int cleared = clearLoggerFactoryMapEntries(lingId, factory, classLoader);
            if (cleared > 0) {
                log.info("[{}] Cleared {} slf4j logger entry/entries loaded by ling CL", lingId, cleared);
            } else {
                log.debug("[{}] No slf4j logger entry loaded by ling CL", lingId);
            }
        } catch (ClassNotFoundException e) {
            log.debug("[{}] slf4j not available, skip", lingId);
        } catch (Exception e) {
            log.debug("[{}] slf4j cleanup failed: {}", lingId, e.getMessage());
        }
    }

    /**
     * 反射清理 ILoggerFactory 内部 Map 中灵元 CL 加载的 logger 条目。
     * <p>
     * 通用处理：枚举 ILoggerFactory 中类型为 Map 的字段，移除 value 由灵元 CL 加载的条目。
     * 适用于 Logback LoggerContext.loggerMap、log4j2 LoggerRegistry 等。
     * 不调用任何 reset()/shutdown()——这些方法会清空全局 logger。
     * <p>
     * <b>线程安全约束</b>：仅在 Map 实例为 {@link ConcurrentHashMap} 时执行 remove。
     * 若目标框架内部使用 HashMap（如部分 log4j2 LoggerRegistry 实现），
     * 并发 getLogger() 与此处 remove 可能导致 HashMap 结构损坏或死循环，
     * 因此遇到非线程安全 Map 类型时跳过清理并告警，避免并发损坏。
     */
    @SuppressWarnings("unchecked")
    private int clearLoggerFactoryMapEntries(String lingId, Object factory, ClassLoader classLoader) {
        int cleared = 0;
        for (Field f : factory.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (!Map.class.isAssignableFrom(f.getType())) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object mapObj = f.get(factory);
                if (!(mapObj instanceof Map)) {
                    continue;
                }
                // 仅对线程安全的 ConcurrentHashMap 执行 remove，避免并发 getLogger() 损坏非线程安全 Map
                if (!(mapObj instanceof ConcurrentHashMap)) {
                    log.warn("[{}] Non-thread-safe Map type {} on field {}, skip clearing to avoid concurrent corruption",
                            lingId, mapObj.getClass().getName(), f.getName());
                    continue;
                }
                Map<Object, Object> map = (Map<Object, Object>) mapObj;
                List<Object> keysToRemove = new ArrayList<>();
                for (Map.Entry<Object, Object> entry : map.entrySet()) {
                    Object val = entry.getValue();
                    if (val != null && isLoadedBy(val.getClass().getClassLoader(), classLoader)) {
                        keysToRemove.add(entry.getKey());
                    }
                }
                for (Object key : keysToRemove) {
                    map.remove(key);
                    cleared++;
                }
            } catch (Exception e) {
                // 忽略单个字段反射失败
                log.trace("[{}] Failed to clear slf4j factory field {}: {}", lingId, f.getName(), e.getMessage());
            }
        }
        return cleared;
    }

    /** 判断 ClassLoader cl 是否由 target 加载（或就是 target） */
    private boolean isLoadedBy(ClassLoader cl, ClassLoader target) {
        while (cl != null) {
            if (cl == target) return true;
            cl = cl.getParent();
        }
        return false;
    }
}
