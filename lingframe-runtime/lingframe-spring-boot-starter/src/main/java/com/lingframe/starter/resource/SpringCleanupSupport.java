package com.lingframe.starter.resource;

import java.lang.ref.Reference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Spring 生态清理共享工具。
 * <p>
 * 提供所有 {@code *Cleaner} 共用的反射探测与 ClassLoader 关联判断，
 * 不含任何业务语义。专用判断器（如 {@code isJacksonRelatedToClassLoader}）
 * 放在对应 Cleaner 内部，不下沉到此处。
 * <p>
 * ConcurrentReferenceHashMap 深清（SoftEntryReference.release）与
 * {@link #isRelatedToClassLoader} 对 Soft/MethodClassKey 的识别，服务于卸载同步排空。
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

    /** 判断对象是否关联目标 ClassLoader（Class / Method / 实例 ClassLoader / MethodClassKey / Soft|Weak|CRHM Entry） */
    static boolean isRelatedToClassLoader(Object obj, ClassLoader targetCL) {
        if (obj == null || targetCL == null)
            return false;
        if (obj instanceof Class<?>)
            return ((Class<?>) obj).getClassLoader() == targetCL;
        // Method / Constructor 持有 declaringClass → ClassLoader，是 Spring 静态缓存常见 key 形态
        // （如 BeanAnnotationHelper.scopedProxyCache、AnnotationUtils.findAnnotationCache）
        if (obj instanceof Method)
            return ((Method) obj).getDeclaringClass().getClassLoader() == targetCL;
        if (obj.getClass().getClassLoader() == targetCL)
            return true;
        if (checkMethodClassKey(obj, targetCL))
            return true;
        // Soft/WeakReference：dump 中常见 SoftReference 拖住灵元 Class
        if (obj instanceof Reference<?>) {
            Object referent = ((Reference<?>) obj).get();
            return isRelatedToClassLoader(referent, targetCL);
        }
        // ConcurrentReferenceHashMap$Entry：key/value 可能是 SoftReference 或 MethodClassKey
        String cn = obj.getClass().getName();
        if (cn.contains("ConcurrentReferenceHashMap$Entry") || cn.endsWith("$Entry")) {
            if (isFieldRelatedToClassLoader(obj, "key", targetCL)
                    || isFieldRelatedToClassLoader(obj, "value", targetCL)) {
                return true;
            }
        }
        return false;
    }

    /** 反射读取字段并判断是否关联目标 ClassLoader */
    static boolean isFieldRelatedToClassLoader(Object obj, String fieldName, ClassLoader targetCL) {
        if (obj == null || fieldName == null) {
            return false;
        }
        try {
            Field f = findFieldInHierarchy(obj.getClass(), fieldName);
            if (f == null) {
                return false;
            }
            f.setAccessible(true);
            return isRelatedToClassLoader(f.get(obj), targetCL);
        } catch (Exception ignored) {
            return false;
        }
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
                totalRemoved += clearMapRelatedToClassLoader(map, cl);
            } catch (Exception ignored) {
            }
        }
        return totalRemoved;
    }

    /**
     * 清理 Map 中与目标 ClassLoader 相关的条目。
     * <p>
     * 兼容 {@code ConcurrentReferenceHashMap}：
     * <ol>
     *   <li>{@code purgeUnreferencedEntries}</li>
     *   <li>按 key/value（含 SoftReference / MethodClassKey）removeIf</li>
     *   <li>深清 CRHM 内部 Entry 的 Soft/Weak referent（否则 Entry 仍钉住 MethodClassKey）</li>
     * </ol>
     */
    static int clearMapRelatedToClassLoader(Map<?, ?> map, ClassLoader cl) {
        if (map == null || cl == null) {
            return 0;
        }
        // ConcurrentReferenceHashMap：清掉已无强引用的 Soft/Weak 条目
        try {
            Method purge = map.getClass().getMethod("purgeUnreferencedEntries");
            purge.invoke(map);
        } catch (Exception ignored) {
            // 非 CRHM 或无此方法
        }
        if (map.isEmpty()) {
            return 0;
        }
        int before = map.size();
        try {
            map.entrySet().removeIf(entry -> isRelatedToClassLoader(entry.getKey(), cl)
                    || isRelatedToClassLoader(entry.getValue(), cl)
                    || isRelatedToClassLoader(entry, cl));
        } catch (UnsupportedOperationException e) {
            try {
                Iterator<?> it = map.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<?, ?> entry = (Map.Entry<?, ?>) it.next();
                    if (isRelatedToClassLoader(entry.getKey(), cl)
                            || isRelatedToClassLoader(entry.getValue(), cl)
                            || isRelatedToClassLoader(entry, cl)) {
                        it.remove();
                    }
                }
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
        int removed = Math.max(0, before - map.size());
        // 关键：CRHM 内部 SoftReference 可能在 remove 后仍短暂持有 MethodClassKey/Class
        removed += deepClearConcurrentReferenceHashMap(map, cl);
        try {
            Method purge = map.getClass().getMethod("purgeUnreferencedEntries");
            purge.invoke(map);
        } catch (Exception ignored) {
        }
        return removed;
    }

    /**
     * 深清 Spring ConcurrentReferenceHashMap。
     * <p>
     * 结构（Spring 6）：{@code segments[]} → {@code Segment.references[]} →
     * {@code SoftEntryReference}（SoftReference&lt;Entry&gt;）链，Entry 强持有 key/value。
     * heap 证据：MethodClassKey 在 Entry.key 上，整条 Entry 被 Soft 引用；
     * 仅 entrySet.removeIf 后 SoftEntryReference 可能仍短暂钉住 Entry。
     * 对关联目标 CL 的链节点调用 {@code release()} 切断 Soft 边。
     */
    static int deepClearConcurrentReferenceHashMap(Map<?, ?> map, ClassLoader cl) {
        if (map == null || cl == null) {
            return 0;
        }
        if (!map.getClass().getName().contains("ConcurrentReferenceHashMap")) {
            return 0;
        }
        int released = 0;
        try {
            Field segmentsField = findFieldInHierarchy(map.getClass(), "segments");
            if (segmentsField == null) {
                return 0;
            }
            segmentsField.setAccessible(true);
            Object segments = segmentsField.get(map);
            if (segments == null || !segments.getClass().isArray()) {
                return 0;
            }
            int segLen = Array.getLength(segments);
            for (int s = 0; s < segLen; s++) {
                Object segment = Array.get(segments, s);
                if (segment == null) {
                    continue;
                }
                Field refsField = findFieldInHierarchy(segment.getClass(), "references");
                if (refsField == null) {
                    continue;
                }
                refsField.setAccessible(true);
                Object refs = refsField.get(segment);
                if (refs == null || !refs.getClass().isArray()) {
                    continue;
                }
                int refLen = Array.getLength(refs);
                for (int i = 0; i < refLen; i++) {
                    Object ref = Array.get(refs, i);
                    released += releaseCrhmReferenceChainIfRelated(ref, cl, 0);
                }
            }
            if (released > 0) {
                log.debug("CRHM deep-release: {} Soft/Weak entry reference(s) released for target CL",
                        released);
            }
        } catch (Exception e) {
            log.trace("deepClearConcurrentReferenceHashMap failed: {}", e.getMessage());
        }
        return released;
    }

    /**
     * 沿 SoftEntryReference 链：Entry.key/value 关联目标 CL 则 release()。
     */
    private static int releaseCrhmReferenceChainIfRelated(Object ref, ClassLoader cl, int depth) {
        if (ref == null || depth > 256 || cl == null) {
            return 0;
        }
        int released = 0;
        Object current = ref;
        while (current != null && depth < 256) {
            depth++;
            try {
                // Reference.get() → Entry
                Method get = current.getClass().getMethod("get");
                Object entry = get.invoke(current);
                boolean related = false;
                if (entry != null) {
                    // Entry 实现 Map.Entry
                    if (entry instanceof Map.Entry<?, ?>) {
                        Map.Entry<?, ?> e = (Map.Entry<?, ?>) entry;
                        related = isRelatedToClassLoader(e.getKey(), cl)
                                || isRelatedToClassLoader(e.getValue(), cl);
                    } else {
                        related = isFieldRelatedToClassLoader(entry, "key", cl)
                                || isFieldRelatedToClassLoader(entry, "value", cl);
                    }
                }
                Method getNext = null;
                try {
                    getNext = current.getClass().getMethod("getNext");
                } catch (NoSuchMethodException ignored) {
                    // ignore
                }
                Object next = getNext != null ? getNext.invoke(current) : null;
                if (related) {
                    try {
                        Method release = current.getClass().getMethod("release");
                        release.invoke(current);
                        released++;
                    } catch (NoSuchMethodException e) {
                        // SoftReference.clear()
                        if (current instanceof Reference<?>) {
                            ((Reference<?>) current).clear();
                            released++;
                        }
                    }
                }
                current = next;
            } catch (Exception e) {
                break;
            }
        }
        return released;
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
