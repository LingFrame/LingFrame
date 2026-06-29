package com.lingframe.starter.resource;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.Map;

/**
 * Spring 生态清理共享工具。
 * <p>
 * 提供所有 {@code *Cleaner} 共用的反射探测与 ClassLoader 关联判断，
 * 不含任何业务语义。专用判断器（如 {@code isJacksonRelatedToClassLoader}）
 * 放在对应 Cleaner 内部，不下沉到此处。
 */
@Slf4j
final class SpringCleanupSupport {

    /** 在类继承链中查找指定名称的字段 */
    static Field findFieldInHierarchy(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /** 在类中查找指定类型的 static 字段 */
    static Field findStaticFieldByType(Class<?> clazz, String typeName) {
        for (Field f : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())
                    && f.getType().getName().equals(typeName)) {
                return f;
            }
        }
        return null;
    }

    /** 判断对象是否关联目标 ClassLoader（Class / 实例 ClassLoader / MethodClassKey） */
    static boolean isRelatedToClassLoader(Object obj, ClassLoader targetCL) {
        if (obj == null || targetCL == null)
            return false;
        if (obj instanceof Class<?>)
            return ((Class<?>) obj).getClassLoader() == targetCL;
        if (obj.getClass().getClassLoader() == targetCL)
            return true;
        return checkMethodClassKey(obj, targetCL);
    }

    /** 检查 MethodClassKey / MethodKey 形式的键是否关联目标 ClassLoader */
    static boolean checkMethodClassKey(Object key, ClassLoader targetCL) {
        try {
            String cn = key.getClass().getName();
            if (cn.contains("MethodClassKey") || cn.contains("MethodKey")) {
                Field mf = findFieldInHierarchy(key.getClass(), "method");
                if (mf != null) {
                    mf.setAccessible(true);
                    Object method = mf.get(key);
                    if (method instanceof Method) {
                        return ((Method) method).getDeclaringClass().getClassLoader() == targetCL;
                    }
                }
                Field tcf = findFieldInHierarchy(key.getClass(), "targetClass");
                if (tcf != null) {
                    tcf.setAccessible(true);
                    Object tc = tcf.get(key);
                    if (tc instanceof Class<?>) {
                        return ((Class<?>) tc).getClassLoader() == targetCL;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 判断 ClassLoader 是否在目标 ClassLoader 的继承链上 */
    static boolean isTargetClassLoader(ClassLoader cl, ClassLoader target) {
        ClassLoader current = cl;
        while (current != null) {
            if (current == target)
                return true;
            current = current.getParent();
        }
        return false;
    }

    /**
     * 检查 value 是否关联目标 ClassLoader。
     * value 可能是 InjectionMetadata、LifecycleMetadata 等对象，内部持有 Class 引用。
     */
    static boolean isValueRelatedToClassLoader(Object value, ClassLoader cl) {
        if (value == null)
            return false;
        if (value.getClass().getClassLoader() == cl)
            return true;
        String[] classFieldNames = { "targetClass", "introspectedClass", "beanClass", "clazz" };
        for (String fieldName : classFieldNames) {
            Field f = findFieldInHierarchy(value.getClass(), fieldName);
            if (f != null) {
                try {
                    f.setAccessible(true);
                    Object fieldValue = f.get(value);
                    if (fieldValue instanceof Class<?>) {
                        if (((Class<?>) fieldValue).getClassLoader() == cl)
                            return true;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    /** 扫描类的所有静态 Map 字段，精确移除目标 ClassLoader 相关条目，返回移除总数 */
    static int removeStaticMapEntries(Class<?> clazz, ClassLoader cl) {
        int totalRemoved = 0;
        for (Field f : clazz.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()))
                continue;
            if (!Map.class.isAssignableFrom(f.getType()))
                continue;
            try {
                f.setAccessible(true);
                Map<?, ?> map = (Map<?, ?>) f.get(null);
                if (map == null || map.isEmpty())
                    continue;
                int before = map.size();
                map.entrySet().removeIf(entry -> isRelatedToClassLoader(entry.getKey(), cl)
                        || isRelatedToClassLoader(entry.getValue(), cl));
                totalRemoved += (before - map.size());
            } catch (Exception ignored) {
            }
        }
        return totalRemoved;
    }

    /** 从对象的所有 Map 类型字段中，移除 key 为目标 ClassLoader 加载的 Class 的条目 */
    static boolean clearMapFieldsByClassLoaderKey(Object obj, ClassLoader cl, String logPrefix) {
        boolean anyCleared = false;
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(f.getType()))
                continue;
            try {
                f.setAccessible(true);
                Map<?, ?> map = (Map<?, ?>) f.get(obj);
                if (map == null || map.isEmpty())
                    continue;
                int before = map.size();
                removeByClassLoaderKey(map, cl);
                int after = map.size();
                if (after < before) {
                    log.info("{} cleared field '{}': {} -> {} entries", logPrefix, f.getName(), before, after);
                    anyCleared = true;
                }
            } catch (Exception e) {
                log.trace("{} failed to clear field '{}': {}", logPrefix, f.getName(), e.getMessage());
            }
        }
        return anyCleared;
    }

    /** 从 Map 中精确移除 key 为目标 ClassLoader 相关的条目 */
    static int removeByClassLoaderKey(Map<?, ?> map, ClassLoader cl) {
        int before = map.size();
        try {
            map.entrySet().removeIf(entry -> {
                Object key = entry.getKey();
                if (key instanceof Class<?>) {
                    return ((Class<?>) key).getClassLoader() == cl;
                }
                if (key instanceof ClassLoader) {
                    return key == cl;
                }
                if (key != null && key.getClass().getClassLoader() == cl) {
                    return true;
                }
                return false;
            });
        } catch (UnsupportedOperationException e) {
            try {
                Iterator<?> it = map.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<?, ?> entry = (Map.Entry<?, ?>) it.next();
                    Object key = entry.getKey();
                    boolean shouldRemove = false;
                    if (key instanceof Class<?>) {
                        shouldRemove = ((Class<?>) key).getClassLoader() == cl;
                    } else if (key instanceof ClassLoader) {
                        shouldRemove = key == cl;
                    }
                    if (shouldRemove)
                        it.remove();
                }
            } catch (Exception ignored) {
            }
        }
        return before - map.size();
    }

    private SpringCleanupSupport() {
    }
}
