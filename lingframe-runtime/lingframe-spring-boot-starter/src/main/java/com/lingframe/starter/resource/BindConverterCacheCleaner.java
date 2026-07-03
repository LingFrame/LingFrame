package com.lingframe.starter.resource;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * BindConverter 静态缓存清理器。
 * <p>
 * Spring Boot 的 {@code org.springframework.boot.context.properties.bind.BindConverter}
 * 持有静态单例（{@code sharedInstance}/{@code INSTANCE}），其内部 Converter 链的
 * {@code defaultEditors} Map 会持有灵元 ClassLoader 下的 PropertyEditor 类，
 * 导致灵元热卸载后 ClassLoader 无法被 GC 回收。
 * <p>
 * 本清理器反射遍历单例内部结构，清掉与目标 ClassLoader 关联的 defaultEditors 条目。
 * <p>
 * 发现来源：{@code SpringLingContainerUnloadRegressionTest} 诊断出的持有链：
 * <pre>
 * BindConverter.sharedInstance
 *   → delegates[0]
 *   → converters.converters{val}
 *   → converters[0]
 *   → matchesOnlyTypeConverter
 *   → defaultEditors{val}
 *   → classLoader
 * </pre>
 */
@Slf4j
final class BindConverterCacheCleaner {

    private static final String BIND_CONVERTER_CLASS =
            "org.springframework.boot.context.properties.bind.BindConverter";

    void clear(String lingId, ClassLoader lingClassLoader) {
        if (lingClassLoader == null) {
            return;
        }
        try {
            Class<?> bindConverterClass = Class.forName(BIND_CONVERTER_CLASS);
            // 策略1：直接重置静态单例字段为 null，强制下次重建（BindConverter 是无状态单例，重建安全）
            boolean reset = resetSingletonField(bindConverterClass, lingId);
            // 策略2：若重置失败，深度遍历清 defaultEditors Map
            int cleared = 0;
            if (!reset) {
                Object singleton = findSingleton(bindConverterClass);
                if (singleton != null) {
                    cleared = clearDefaultEditorsDeep(singleton, lingClassLoader, lingId, 0);
                }
            }
            if (reset || cleared > 0) {
                log.info("[{}] Cleared BindConverter static cache (reset={}, maps={})", lingId, reset, cleared);
            } else {
                log.info("[{}] BindConverter cleanup: no singleton field reset, cleared 0 Maps", lingId);
            }
        } catch (ClassNotFoundException e) {
            log.trace("[{}] BindConverter not on classpath (Spring Boot < 2.x?), skip", lingId);
        } catch (Throwable e) {
            log.warn("[{}] BindConverter cache cleanup failed: {}", lingId, e.getMessage());
        }
    }

    /**
     * 重置 BindConverter 的静态单例字段为 null。
     * 需先移除 final 修饰符（通过反射改 modifiers 字段，JDK 8 兼容）。
     */
    private boolean resetSingletonField(Class<?> bindConverterClass, String lingId) {
        String[] candidateNames = { "sharedInstance", "INSTANCE", "instance" };
        for (String name : candidateNames) {
            try {
                Field f = bindConverterClass.getDeclaredField(name);
                if (!Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                // JDK 8: 通过 modifiers 字段移除 final
                if (Modifier.isFinal(f.getModifiers())) {
                    try {
                        Field modifiers = Field.class.getDeclaredField("modifiers");
                        modifiers.setAccessible(true);
                        modifiers.setInt(f, f.getModifiers() & ~Modifier.FINAL);
                    } catch (Throwable ignored) {
                    }
                }
                f.set(null, null);
                return true;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable e) {
                log.debug("[{}] Failed to reset BindConverter field {}: {}", lingId, name, e.getMessage());
            }
        }
        return false;
    }

    /**
     * 定位 BindConverter 静态单例字段（不同 Spring Boot 版本字段名不同）。
     */
    private Object findSingleton(Class<?> bindConverterClass) throws IllegalAccessException {
        String[] candidateNames = { "sharedInstance", "INSTANCE", "instance" };
        for (String name : candidateNames) {
            try {
                Field f = bindConverterClass.getDeclaredField(name);
                if (Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val != null) {
                        return val;
                    }
                }
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /**
     * 检查对象是否直接或通过字段持有目标 ClassLoader。
     * 诊断链显示 {@code defaultEditors{val}.classLoader}，故需查 classLoader 字段。
     */
    private boolean isHoldingTargetClassLoader(Object value, ClassLoader target) {
        if (value == null || target == null) {
            return false;
        }
        // 1. value 自身的 Class 由目标 CL 加载
        if (value.getClass().getClassLoader() == target) {
            return true;
        }
        // 2. value 有 classLoader 字段等于目标 CL
        Class<?> c = value.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if ("classLoader".equals(f.getName()) || "cl".equals(f.getName())) {
                    try {
                        f.setAccessible(true);
                        Object fieldValue = f.get(value);
                        if (fieldValue == target) {
                            return true;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            c = c.getSuperclass();
        }
        return false;
    }

    /**
     * 深度遍历对象图，清掉所有 value 的 classLoader == 目标 CL 的 Map（defaultEditors）。
     * 限制深度避免无限递归。
     */
    private int clearDefaultEditorsDeep(Object node, ClassLoader target, String lingId, int depth) {
        if (node == null || depth > 8) {
            return 0;
        }
        int cleared = 0;
        // 已处理集合避免环
        List<Object> visited = new ArrayList<>();
        return clearDefaultEditorsDeep(node, target, lingId, depth, visited);
    }

    private int clearDefaultEditorsDeep(Object node, ClassLoader target, String lingId, int depth, List<Object> visited) {
        if (node == null || depth > 8) {
            return 0;
        }
        // 用 IdentityHashCode 去重（避免 equals 触发副作用）
        for (Object v : visited) {
            if (v == node) {
                return 0;
            }
        }
        visited.add(node);

        int cleared = 0;
        Class<?> cls = node.getClass();

        // 1. 若 node 是 Map，检查其 value 是否持有目标 CL（直接或通过 classLoader 字段），是则清空
        if (node instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) node;
            boolean related = false;
            for (Object v : map.values()) {
                if (v != null && isHoldingTargetClassLoader(v, target)) {
                    related = true;
                    break;
                }
            }
            if (related) {
                map.clear();
                cleared++;
                log.debug("[{}] Cleared Map (size was {}) holding ling CL at depth {}", lingId, map.size(), depth);
            }
        }

        // 2. 遍历所有非静态实例字段，递归
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object val = f.get(node);
                    if (val != null) {
                        cleared += clearDefaultEditorsDeep(val, target, lingId, depth + 1, visited);
                    }
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }

        // 3. 若是 Collection，遍历元素
        if (node instanceof Collection<?>) {
            for (Object el : (Collection<?>) node) {
                if (el != null) {
                    cleared += clearDefaultEditorsDeep(el, target, lingId, depth + 1, visited);
                }
            }
        }

        // 4. 若是数组，遍历元素
        if (cls.isArray() && !cls.getComponentType().isPrimitive()) {
            int len = Array.getLength(node);
            for (int i = 0; i < len; i++) {
                Object el = Array.get(node, i);
                if (el != null) {
                    cleared += clearDefaultEditorsDeep(el, target, lingId, depth + 1, visited);
                }
            }
        }

        return cleared;
    }
}
