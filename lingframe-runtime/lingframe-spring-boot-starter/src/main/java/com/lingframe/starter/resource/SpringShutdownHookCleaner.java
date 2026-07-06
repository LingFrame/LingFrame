package com.lingframe.starter.resource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 清理 Spring ShutdownHook 对灵元 Context / ClassLoader 的残留引用。
 * <p>
 * 三部分：ApplicationContext shutdownHook 字段、SpringApplicationShutdownHook 静态引用、
 * java.lang.ApplicationShutdownHooks 注册表。
 * <p>
 * 专用判断器 {@link #isSpringShutdownHook}、{@link #isShutdownHookTargetReference}、
 * {@link #deepReferencesShutdownHookTarget} 留在本类内部。
 */
@Slf4j
final class SpringShutdownHookCleaner {

    void clear(String lingId, ClassLoader lingClassLoader, ConfigurableApplicationContext lingContext) {
        clearApplicationContextShutdownHook(lingId, lingContext);
        clearSpringBootShutdownHookReferences(lingId, lingClassLoader);
        clearApplicationShutdownHooksRegistry(lingId, lingClassLoader);
    }

    private void clearApplicationContextShutdownHook(String lingId, ConfigurableApplicationContext lingContext) {
        if (lingContext == null) {
            return;
        }
        try {
            Field shutdownHookField = SpringCleanupSupport.findFieldInHierarchy(lingContext.getClass(), "shutdownHook");
            if (shutdownHookField == null) {
                return;
            }
            shutdownHookField.setAccessible(true);
            Object hookObject = shutdownHookField.get(lingContext);
            if (!(hookObject instanceof Thread)) {
                return;
            }
            Thread hook = (Thread) hookObject;
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
                log.info("[{}] Removed ApplicationContext shutdown hook: {}", lingId, hook.getName());
            } catch (IllegalStateException ignored) {
                // JVM 正在退出时无法移除，仍然继续断开 context 持有的引用。
            } catch (IllegalArgumentException ignored) {
                // hook 未注册到 Runtime 时会进入这里，仍然需要清空字段引用。
            }
            shutdownHookField.set(lingContext, null);
        } catch (Exception e) {
            log.debug("[{}] ApplicationContext shutdown hook cleanup failed: {}", lingId, e.getMessage());
        }
    }

    private void clearSpringBootShutdownHookReferences(String lingId, ClassLoader lingClassLoader) {
        try {
            Class<?> hookClass = Class.forName("org.springframework.boot.SpringApplicationShutdownHook");
            int removed = 0;

            // 获取 SpringApplication.shutdownHook 静态字段中的单例实例
            // SpringApplicationShutdownHook 的 contexts/closedContexts 是实例字段，
            // 必须通过 SpringApplication.shutdownHook 静态字段拿到单例后才能清理
            Object shutdownHookInstance = getSpringApplicationShutdownHookInstance();
            if (shutdownHookInstance != null) {
                removed += removeShutdownHookTargetReferences(
                        lingId,
                        "SpringApplicationShutdownHook.instance",
                        hookClass,
                        shutdownHookInstance,
                        false,
                        lingClassLoader);
            }

            // 保留静态字段扫描（兼容未来版本可能的变化）
            removed += removeShutdownHookTargetReferences(lingId, "SpringApplicationShutdownHook(static)",
                    hookClass, null, true, lingClassLoader);
            for (Field field : hookClass.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (!hookClass.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object holder = field.get(null);
                    if (holder != null) {
                        removed += removeShutdownHookTargetReferences(
                                lingId,
                                "SpringApplicationShutdownHook(" + field.getName() + ")",
                                holder.getClass(),
                                holder,
                                false,
                                lingClassLoader);
                    }
                } catch (Exception ignored) {
                }
            }
            if (removed > 0) {
                log.info("[{}] Removed {} Spring Boot shutdown hook reference(s)", lingId, removed);
            }
        } catch (ClassNotFoundException ignored) {
            // 当前运行环境没有 Spring Boot shutdown hook 实现。
        } catch (Exception e) {
            log.debug("[{}] Spring Boot shutdown hook cleanup failed: {}", lingId, e.getMessage());
        }
    }

    /**
     * 通过反射获取 {@code SpringApplication.shutdownHook} 静态字段中的
     * {@code SpringApplicationShutdownHook} 单例实例。
     *
     * @return 单例实例，若不存在或无法访问则返回 {@code null}
     */
    private Object getSpringApplicationShutdownHookInstance() {
        try {
            Class<?> springAppClass = Class.forName("org.springframework.boot.SpringApplication");
            Field shutdownHookField = springAppClass.getDeclaredField("shutdownHook");
            shutdownHookField.setAccessible(true);
            return shutdownHookField.get(null);
        } catch (Exception e) {
            log.trace("Failed to access SpringApplication.shutdownHook: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void clearApplicationShutdownHooksRegistry(String lingId, ClassLoader lingClassLoader) {
        try {
            Class<?> hooksClass = Class.forName("java.lang.ApplicationShutdownHooks");
            Field hooksField = hooksClass.getDeclaredField("hooks");
            hooksField.setAccessible(true);
            Map<Thread, Thread> hooks = (Map<Thread, Thread>) hooksField.get(null);
            synchronized (hooksClass) {
                List<Thread> toRemove = new ArrayList<>();
                for (Thread hook : hooks.keySet()) {
                    if (isSpringShutdownHook(hook, lingClassLoader)) {
                        toRemove.add(hook);
                    }
                }
                for (Thread hook : toRemove) {
                    hooks.remove(hook);
                    log.info("[{}] Removed ShutdownHook: {} (class={})",
                            lingId, hook.getName(), hook.getClass().getName());
                }
            }
        } catch (Exception e) {
            log.debug("[{}] ShutdownHook cleanup failed: {}", lingId, e.getMessage());
        }
    }

    private int removeShutdownHookTargetReferences(String lingId,
            String holderName,
            Class<?> holderClass,
            Object holder,
            boolean staticOnly,
            ClassLoader lingClassLoader) {
        return removeShutdownHookTargetReferences(lingId, holderName, holderClass, holder,
                staticOnly, lingClassLoader, new IdentityHashMap<>());
    }

    private int removeShutdownHookTargetReferences(String lingId,
            String holderName,
            Class<?> holderClass,
            Object holder,
            boolean staticOnly,
            ClassLoader lingClassLoader,
            IdentityHashMap<Object, Boolean> visited) {
        // 防止循环引用导致 StackOverflow（如 Handlers 内部类反向引用父 SpringApplicationShutdownHook）
        if (holder != null && visited.put(holder, Boolean.TRUE) != null) {
            return 0;
        }
        int removed = 0;
        for (Field field : holderClass.getDeclaredFields()) {
            if (staticOnly != Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (!staticOnly && Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (!Collection.class.isAssignableFrom(field.getType()) && !Map.class.isAssignableFrom(field.getType())) {
                String typeName = field.getType().getName();
                if (typeName.contains("SpringApplicationShutdownHook") || typeName.contains("Handlers")) {
                    try {
                        field.setAccessible(true);
                        Object innerHolder = field.get(holder);
                        if (innerHolder != null) {
                            removed += removeShutdownHookTargetReferences(lingId,
                                    holderName + "." + field.getName(),
                                    innerHolder.getClass(),
                                    innerHolder,
                                    false,
                                    lingClassLoader,
                                    visited);
                        }
                    } catch (Exception ignored) {
                    }
                }
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(holder);
                int currentRemoved = 0;
                if (value instanceof Collection<?>) {
                    currentRemoved = removeFromCollection((Collection<?>) value, lingClassLoader);
                } else if (value instanceof Map<?, ?>) {
                    currentRemoved = removeFromMap((Map<?, ?>) value, lingClassLoader);
                }
                removed += currentRemoved;
                if (currentRemoved > 0) {
                    log.info("[{}] Cleared {} shutdown hook reference(s) from {}.{}",
                            lingId, currentRemoved, holderName, field.getName());
                }
            } catch (Exception ignored) {
            }
        }
        return removed;
    }

    private int removeFromCollection(Collection<?> collection, ClassLoader lingClassLoader) {
        List<Object> toRemove = new ArrayList<>();
        for (Object candidate : collection) {
            if (isShutdownHookTargetReference(candidate, lingClassLoader)) {
                toRemove.add(candidate);
            }
        }
        if (toRemove.isEmpty()) {
            return 0;
        }
        collection.removeAll(toRemove);
        return toRemove.size();
    }

    private int removeFromMap(Map<?, ?> map, ClassLoader lingClassLoader) {
        List<Object> keysToRemove = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (isShutdownHookTargetReference(entry.getKey(), lingClassLoader)
                    || isShutdownHookTargetReference(entry.getValue(), lingClassLoader)) {
                keysToRemove.add(entry.getKey());
            }
        }
        if (keysToRemove.isEmpty()) {
            return 0;
        }
        for (Object key : keysToRemove) {
            map.remove(key);
        }
        return keysToRemove.size();
    }

    private boolean isShutdownHookTargetReference(Object candidate, ClassLoader lingClassLoader) {
        if (candidate == null) {
            return false;
        }
        if (candidate instanceof ConfigurableApplicationContext) {
            ConfigurableApplicationContext context = (ConfigurableApplicationContext) candidate;
            try {
                if (context.getClassLoader() == lingClassLoader) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        if (candidate instanceof Thread && isSpringShutdownHook((Thread) candidate, lingClassLoader)) {
            return true;
        }
        if (SpringCleanupSupport.isRelatedToClassLoader(candidate, lingClassLoader)) {
            return true;
        }
        return deepReferencesShutdownHookTarget(candidate, lingClassLoader, 3, new IdentityHashMap<>());
    }

    private boolean deepReferencesShutdownHookTarget(Object candidate,
            ClassLoader lingClassLoader,
            int depth,
            IdentityHashMap<Object, Boolean> visited) {
        if (candidate == null || depth <= 0) {
            return false;
        }
        if (visited.put(candidate, Boolean.TRUE) != null) {
            return false;
        }
        if (candidate instanceof ClassLoader) {
            return candidate == lingClassLoader;
        }
        if (candidate instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) candidate) {
                if (deepReferencesShutdownHookTarget(item, lingClassLoader, depth - 1, visited)) {
                    return true;
                }
            }
            return false;
        }
        if (candidate instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) candidate).entrySet()) {
                if (deepReferencesShutdownHookTarget(entry.getKey(), lingClassLoader, depth - 1, visited)
                        || deepReferencesShutdownHookTarget(entry.getValue(), lingClassLoader, depth - 1, visited)) {
                    return true;
                }
            }
            return false;
        }
        Class<?> type = candidate.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object fieldValue = field.get(candidate);
                    if (fieldValue == lingClassLoader) {
                        return true;
                    }
                    if (deepReferencesShutdownHookTarget(fieldValue, lingClassLoader, depth - 1, visited)) {
                        return true;
                    }
                } catch (Exception ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private boolean isSpringShutdownHook(Thread hook, ClassLoader lingClassLoader) {
        String name = hook.getName();
        String className = hook.getClass().getName();
        boolean isSpringHook = name.contains("SpringApplicationShutdownHook")
                || className.contains("SpringApplicationShutdownHook")
                || className.contains("SpringContextShutdownHook");
        if (!isSpringHook)
            return false;
        if (hook.getClass().getClassLoader() == lingClassLoader)
            return true;
        // 检查 target
        try {
            Field targetField = Thread.class.getDeclaredField("target");
            targetField.setAccessible(true);
            Object target = targetField.get(hook);
            if (target != null && target.getClass().getClassLoader() == lingClassLoader) {
                return true;
            }
        } catch (NoSuchFieldException e) {
            // 在 Java 21+ 中，`target` 字段可能已经不存在
        } catch (Exception ignored) {
        }
        // 检查 contextClassLoader
        try {
            if (hook.getContextClassLoader() == lingClassLoader)
                return true;
        } catch (Exception ignored) {
        }
        return false;
    }
}
