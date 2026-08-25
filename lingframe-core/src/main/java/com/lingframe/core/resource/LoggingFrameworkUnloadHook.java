package com.lingframe.core.resource;

import com.lingframe.core.spi.LingUnloadHook;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * 日志框架与内省引用卸载钩子。
 * <p>
 * 主动清理 logback、log4j2、java.util.logging、slf4j 中关联目标 ClassLoader 的常驻引用。
 * <p>
 * <b>核心安全与兜底约束</b>：
 * <ul>
 *   <li>无论日志工厂是由灵元 CL 还是宿主 CL 加载，均深入其并发缓存容器，精准逐出由灵元类名或由灵元 CL 加载的 Logger/Appender 条目；</li>
 *   <li>绝不对宿主全局 LoggerContext / LogManager 执行 shutdown()/reset()，保证宿主全局日志体系绝对安全；</li>
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

        Set<String> loadedClassNames = extractLoadedClassNames(classLoader);

        cleanupLogback(lingId, classLoader, loadedClassNames);
        cleanupLog4j2(lingId, classLoader, loadedClassNames);
        cleanupJul(lingId, classLoader, loadedClassNames);
        cleanupSlf4j(lingId, classLoader, loadedClassNames);

        log.info("[{}] Logging framework cleanup completed", lingId);
    }

    // =========================================================================
    // logback 清理
    // =========================================================================

    /**
     * 清理 logback LoggerContext。
     * <p>
     * 1. 若 LoggerContext 由灵元自身加载：执行 shutdown/reset。
     * 2. 若 LoggerContext 属于宿主（常见场景）：精准清理其内部 loggerCache 中属于该灵元的条目与 Appender。
     */
    private void cleanupLogback(String lingId, ClassLoader classLoader, Set<String> loadedClassNames) {
        try {
            Class<?> loggerFactoryClass = Class.forName("org.slf4j.LoggerFactory");
            Method getFactoryMethod = loggerFactoryClass.getMethod("getILoggerFactory");
            Object factory = getFactoryMethod.invoke(null);
            if (factory == null) {
                return;
            }

            ClassLoader factoryCL = factory.getClass().getClassLoader();
            String factoryClassName = factory.getClass().getName();

            if (factoryCL == classLoader && factoryClassName.contains("LoggerContext")) {
                // 灵元独占的 LoggerContext，安全 shutdown
                try {
                    Method shutdownMethod = factory.getClass().getMethod("shutdown");
                    shutdownMethod.invoke(factory);
                    log.info("[{}] Shutdown isolated logback LoggerContext", lingId);
                    return;
                } catch (NoSuchMethodException e) {
                    try {
                        Method resetMethod = factory.getClass().getMethod("reset");
                        resetMethod.invoke(factory);
                        log.info("[{}] Reset isolated logback LoggerContext", lingId);
                        return;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }

            // 宿主共享的 LoggerContext：精准剔除灵元关联的 Logger 条目
            int cleared = clearLoggerFactoryMapEntries(lingId, factory, classLoader, loadedClassNames);
            if (cleared > 0) {
                log.info("[{}] Cleared {} logback logger cache entry/entries for ling", lingId, cleared);
            }
        } catch (ClassNotFoundException e) {
            log.debug("[{}] logback not available, skip", lingId);
        } catch (Exception e) {
            log.debug("[{}] logback cleanup failed: {}", lingId, e.getMessage());
        }
    }

    // =========================================================================
    // log4j2 清理
    // =========================================================================

    private void cleanupLog4j2(String lingId, ClassLoader classLoader, Set<String> loadedClassNames) {
        try {
            Class<?> logManagerClass = Class.forName("org.apache.logging.log4j.LogManager");
            if (logManagerClass.getClassLoader() == classLoader) {
                try {
                    Method shutdownMethod = logManagerClass.getMethod("shutdown");
                    shutdownMethod.invoke(null);
                    log.info("[{}] Shutdown isolated log4j2 LogManager", lingId);
                    return;
                } catch (Exception ignored) {
                }
            }
            // 宿主共享模式暂无需特殊额外处理
        } catch (ClassNotFoundException e) {
            log.debug("[{}] log4j2 not available, skip", lingId);
        } catch (Exception e) {
            log.debug("[{}] log4j2 cleanup failed: {}", lingId, e.getMessage());
        }
    }

    // =========================================================================
    // java.util.logging 清理
    // =========================================================================

    private void cleanupJul(String lingId, ClassLoader classLoader, Set<String> loadedClassNames) {
        try {
            LogManager logManager = LogManager.getLogManager();
            int cleared = 0;
            try {
                Field loggersField = logManager.getClass().getDeclaredField("loggers");
                loggersField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, Logger> loggers = (Map<String, Logger>) loggersField.get(logManager);
                if (loggers != null) {
                    List<String> names = new ArrayList<>(loggers.keySet());
                    for (String name : names) {
                        Logger logger = loggers.get(name);
                        if (logger == null) {
                            continue;
                        }
                        boolean match = isLoadedBy(logger.getClass().getClassLoader(), classLoader)
                                || loadedClassNames.contains(name)
                                || (lingId != null && name.contains(lingId));
                        if (match) {
                            if (loggers instanceof ConcurrentHashMap) {
                                loggers.remove(name);
                            }
                            logger.setUseParentHandlers(false);
                            for (Handler h : logger.getHandlers()) {
                                logger.removeHandler(h);
                            }
                            cleared++;
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
            }
        } catch (Exception e) {
            log.debug("[{}] JUL cleanup failed: {}", lingId, e.getMessage());
        }
    }

    // =========================================================================
    // slf4j 清理
    // =========================================================================

    private void cleanupSlf4j(String lingId, ClassLoader classLoader, Set<String> loadedClassNames) {
        try {
            Class<?> loggerFactoryClass = Class.forName("org.slf4j.LoggerFactory");
            Method getFactoryMethod = loggerFactoryClass.getMethod("getILoggerFactory");
            Object factory = getFactoryMethod.invoke(null);
            if (factory == null) {
                return;
            }

            int cleared = clearLoggerFactoryMapEntries(lingId, factory, classLoader, loadedClassNames);
            if (cleared > 0) {
                log.info("[{}] Cleared {} slf4j logger entry/entries for ling", lingId, cleared);
            }
        } catch (ClassNotFoundException e) {
            log.debug("[{}] slf4j not available, skip", lingId);
        } catch (Exception e) {
            log.debug("[{}] slf4j cleanup failed: {}", lingId, e.getMessage());
        }
    }

    /**
     * 反射扫描并清理 ILoggerFactory 内部 Map（如 loggerCache、loggerMap）中属于灵元的条目。
     */
    @SuppressWarnings("unchecked")
    private int clearLoggerFactoryMapEntries(String lingId, Object factory, ClassLoader classLoader, Set<String> loadedClassNames) {
        int cleared = 0;
        Class<?> current = factory.getClass();
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !Map.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object mapObj = f.get(factory);
                    if (!(mapObj instanceof ConcurrentHashMap)) {
                        continue;
                    }
                    Map<Object, Object> map = (Map<Object, Object>) mapObj;
                    List<Object> keysToRemove = new ArrayList<>();
                    for (Map.Entry<Object, Object> entry : map.entrySet()) {
                        Object key = entry.getKey();
                        Object val = entry.getValue();
                        boolean match = false;
                        if (key instanceof String) {
                            String keyStr = (String) key;
                            if (loadedClassNames.contains(keyStr) || (lingId != null && keyStr.contains(lingId))) {
                                match = true;
                            }
                        } else if (key instanceof Class) {
                            Class<?> clazz = (Class<?>) key;
                            if (isLoadedBy(clazz.getClassLoader(), classLoader)) {
                                match = true;
                            }
                        }
                        if (!match && val != null) {
                            if (isLoadedBy(val.getClass().getClassLoader(), classLoader)) {
                                match = true;
                            }
                        }
                        if (match) {
                            keysToRemove.add(key);
                        }
                    }
                    for (Object key : keysToRemove) {
                        map.remove(key);
                        cleared++;
                    }
                } catch (Exception e) {
                    log.trace("[{}] Failed to inspect logger factory field {}: {}", lingId, f.getName(), e.getMessage());
                }
            }
            current = current.getSuperclass();
        }
        return cleared;
    }

    /**
     * 通过反射提取 ClassLoader 内部已加载的所有类名（Vector<Class<?>> classes）。
     */
    @SuppressWarnings("unchecked")
    private Set<String> extractLoadedClassNames(ClassLoader classLoader) {
        if (classLoader == null) {
            return Collections.emptySet();
        }
        Set<String> classNames = new HashSet<>();
        try {
            Class<?> clClass = ClassLoader.class;
            Field classesField = clClass.getDeclaredField("classes");
            classesField.setAccessible(true);
            Vector<Class<?>> classes = (Vector<Class<?>>) classesField.get(classLoader);
            if (classes != null) {
                synchronized (classes) {
                    for (Class<?> c : classes) {
                        if (c != null) {
                            classNames.add(c.getName());
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return classNames;
    }

    private boolean isLoadedBy(ClassLoader cl, ClassLoader target) {
        while (cl != null) {
            if (cl == target) {
                return true;
            }
            cl = cl.getParent();
        }
        return false;
    }
}
