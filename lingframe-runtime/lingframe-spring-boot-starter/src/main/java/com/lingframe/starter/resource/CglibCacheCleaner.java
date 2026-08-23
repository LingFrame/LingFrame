package com.lingframe.starter.resource;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import lombok.extern.slf4j.Slf4j;

/**
 * 清理 Spring CGLIB {@code AbstractClassGenerator} 静态缓存与 MethodProxy 残留。
 * <p>
 * Spring 5.x / 6.x 的 CACHE 结构一致：{@code Map<ClassLoader, ClassLoaderData>}，
 * {@code ClassLoaderData.generatedClasses} 为 {@code LoadingCache}，底层
 * {@code ConcurrentMap} 的 value 多为 {@code WeakReference<Class>}（或 Future / Class）。
 * <p>
 * 泄漏链（生产路径 dispatch 后尤其明显）：
 * <pre>
 * 灵元CL → (JVM classes) → Xxx$$SpringCGLIB$$0
 *   → static MethodProxy
 *   → createInfo.strategy (BeanFactoryAwareGeneratorStrategy，灵核类)
 *   → beanFactory → 灵元CL
 * </pre>
 * 仅 remove CACHE 条目不够：增强类仍由 JVM 持有，MethodProxy 静态字段仍指向策略。
 * 必须在 remove 前从 ClassLoaderData 抽出生成类，清空 MethodProxy 的 createInfo/fastClassInfo
 *（并尽量把 static 字段置 null）。
 * <p>
 * 清理边界（防误伤灵核，务必保持）：CGLIB 生成类命名确定（{@code Xxx$$SpringCGLIB$$0}），
 * 共享边界（父委派的 starter/测试类）下灵元侧与灵核侧会复用同一个由灵核类加载器定义的增强类。
 * 该共享类持有的是灵核自己的 beanFactory/策略，不是灵元泄漏源；清空其 createInfo 会让
 * 灵核后续所有 Spring 上下文在 CGLIB {@code MethodProxy.init} 抛 NPE。因此只清理
 * 「由灵元类加载器定义」的增强类，经父委派解析回灵核的类一律跳过。
 * <p>
 * 命名标识：
 * <ul>
 *   <li>Spring 6：{@code $$SpringCGLIB$$} / {@code $$SpringCGLIB$$FastClass$$}</li>
 *   <li>Spring 5 及更早：{@code $$EnhancerBySpringCGLIB$$}</li>
 * </ul>
 * JDK 17+ 下 {@code ClassLoader.classes} 字段不可访问，不能依赖该兜底；主路径必须走 CACHE。
 */
@Slf4j
final class CglibCacheCleaner {

    /** Spring 6 NamingPolicy 标记 */
    private static final String SPRING6_CGLIB_MARKER = "$$SpringCGLIB$$";

    /** Spring 5 / 历史 Enhancer 标记 */
    private static final String SPRING5_ENHANCER_MARKER = "$$EnhancerBySpringCGLIB$$";

    /** Spring CGLIB MethodProxy 全限定类名 */
    private static final String METHOD_PROXY_CLASS_NAME =
            "org.springframework.cglib.proxy.MethodProxy";

    private static final String ABSTRACT_CLASS_GENERATOR =
            "org.springframework.cglib.core.AbstractClassGenerator";

    private final int springMajorVersion;

    /** sun.misc.Unsafe 实例（反射获取；启动需 --add-opens java.base/sun.misc=ALL-UNNAMED） */
    private static final Object UNSAFE = resolveUnsafe();

    CglibCacheCleaner(int springMajorVersion) {
        this.springMajorVersion = springMajorVersion;
    }

    private static Object resolveUnsafe() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 将静态字段置 null：普通字段直接反射；final 字段反射失败时用 Unsafe 兜底
     *（JDK 17 下 final static 字段反射 set 会抛 IllegalAccessException，必须走 Unsafe.putObject）。
     */
    private static void setStaticFieldNull(Field f, Object targetClass, String fieldDesc) {
        try {
            f.setAccessible(true);
            f.set(null, null);
        } catch (Throwable reflectFailed) {
            if (UNSAFE != null) {
                try {
                    // static 字段必须用 staticFieldOffset（objectFieldOffset 对 static 字段是错误偏移）
                    Method offsetM = UNSAFE.getClass().getMethod("staticFieldOffset", Field.class);
                    long offset = (Long) offsetM.invoke(UNSAFE, f);
                    Method putM = UNSAFE.getClass().getMethod("putObject", Object.class, long.class, Object.class);
                    // static 字段首参为声明类 Class 对象
                    putM.invoke(UNSAFE, targetClass, offset, (Object) null);
                } catch (Throwable unsafeFailed) {
                    log.trace("[cglib-cleaner] Unsafe nullify failed for {}: {}", fieldDesc, unsafeFailed.getMessage());
                }
            } else {
                log.trace("[cglib-cleaner] Cannot nullify {}: {}", fieldDesc, reflectFailed.getMessage());
            }
        }
    }

    void clear(String lingId, ClassLoader lingClassLoader) {
        if (lingClassLoader == null) {
            return;
        }
        // 5.x / 6.x 结构相同：先从 ClassLoaderData 抽生成类清 MethodProxy，再 remove CACHE
        boolean removed = clearCglibCacheByClassLoaderData(lingId, lingClassLoader);
        if (!removed) {
            // 结构异常时兜底：只 remove 静态 Map 条目（无法再抽 MethodProxy）
            clearCglibCacheGeneric(lingId, lingClassLoader);
        }
        // JDK 8 兜底：CL.classes 扫描；JDK 17+ 字段不可访问，内部会安静跳过
        clearMethodProxyViaClassLoaderClasses(lingId, lingClassLoader);
    }

    /**
     * 主路径：CACHE → ClassLoaderData → LoadingCache.map → 生成 Class → 清 MethodProxy → remove。
     *
     * @return true 表示 CACHE 字段存在且处理完成（无论是否有该 CL 的条目）
     */
    private boolean clearCglibCacheByClassLoaderData(String lingId, ClassLoader lingClassLoader) {
        try {
            Class<?> generatorClass = Class.forName(ABSTRACT_CLASS_GENERATOR);
            Field cacheField = generatorClass.getDeclaredField("CACHE");
            cacheField.setAccessible(true);
            Object cache = cacheField.get(null);
            if (!(cache instanceof Map<?, ?>)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>) cache;
            Object classLoaderData = map.get(lingClassLoader);
            if (classLoaderData != null) {
                clearMethodProxyFromClassLoaderData(lingId, classLoaderData);
            }
            Object removed = map.remove(lingClassLoader);
            if (removed != null) {
                log.info("[{}] Cleared CGLIB CACHE entry (Spring {}.x ClassLoaderData)",
                        lingId, springMajorVersion >= 6 ? 6 : 5);
            }
            return true;
        } catch (NoSuchFieldException e) {
            log.debug("[{}] AbstractClassGenerator.CACHE not found: {}", lingId, e.getMessage());
            return false;
        } catch (ClassNotFoundException e) {
            log.trace("[{}] CGLIB not on classpath", lingId);
            return true;
        } catch (Exception e) {
            log.debug("[{}] CGLIB ClassLoaderData cleanup failed: {}", lingId, e.getMessage());
            return false;
        }
    }

    /**
     * 从 ClassLoaderData 提取已生成的 CGLIB 类，清理 MethodProxy。
     * <p>
     * 双通道：
     * <ol>
     *   <li>{@code generatedClasses} LoadingCache 的 value（Class / WeakReference / Future）</li>
     *   <li>{@code reservedClassNames} + {@code Class.forName(name, false, lingCL)}
     *       —— JDK 17 无 {@code ClassLoader.classes}，LoadingCache 的 WeakRef 可能已空，
     *       但增强类仍存活并持有 static MethodProxy；reserved 名表是可靠入口</li>
     * </ol>
     */
    private void clearMethodProxyFromClassLoaderData(String lingId, Object classLoaderData) {
        try {
            ClassLoader lingCl = resolveClassLoaderFromData(classLoaderData);
            int classCount = 0;
            int cleaned = 0;
            Set<Class<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());

            // 通道 1：LoadingCache values
            Field generatedClassesField = classLoaderData.getClass().getDeclaredField("generatedClasses");
            generatedClassesField.setAccessible(true);
            Object loadingCache = generatedClassesField.get(classLoaderData);
            Map<?, ?> map = null;
            if (loadingCache != null) {
                Field mapField = loadingCache.getClass().getDeclaredField("map");
                mapField.setAccessible(true);
                map = (Map<?, ?>) mapField.get(loadingCache);
                if (map != null) {
                    for (Object value : map.values()) {
                        Class<?> generatedClass = unwrapGeneratedClass(value);
                        if (generatedClass == null || !seen.add(generatedClass)) {
                            continue;
                        }
                        // 灵核定义的共享增强类不属于本灵元泄漏源，跳过（见类注释「清理边界」）
                        if (lingCl != null && generatedClass.getClassLoader() != lingCl) {
                            continue;
                        }
                        classCount++;
                        if (isSpringCglibGenerated(generatedClass.getName())) {
                            cleaned += clearMethodProxyStaticFields(lingId, generatedClass);
                        }
                    }
                }
            }

            // 通道 2：reservedClassNames → forName（JDK 17 关键）
            cleaned += clearMethodProxyViaReservedNames(lingId, classLoaderData, lingCl, seen);
            classCount = Math.max(classCount, seen.size());

            // 清空 LoadingCache 内 map，断开 data → WeakRef/Class 的缓存边
            if (map != null) {
                try {
                    map.clear();
                } catch (UnsupportedOperationException ignored) {
                    // 不可变 map 则跳过
                }
            }
            if (classCount > 0 || cleaned > 0) {
                log.info("[{}] ClassLoaderData MethodProxy cleanup: generatedClasses={}, MethodProxyFields={}",
                        lingId, classCount, cleaned);
            }
        } catch (NoSuchFieldException e) {
            log.debug("[{}] ClassLoaderData structure not as expected: {}", lingId, e.getMessage());
        } catch (Exception e) {
            log.debug("[{}] Failed to extract classes from ClassLoaderData: {}", lingId, e.getMessage());
        }
    }

    private ClassLoader resolveClassLoaderFromData(Object classLoaderData) {
        try {
            Field clField = classLoaderData.getClass().getDeclaredField("classLoader");
            clField.setAccessible(true);
            Object ref = clField.get(classLoaderData);
            if (ref instanceof Reference<?>) {
                Object cl = ((Reference<?>) ref).get();
                if (cl instanceof ClassLoader) {
                    return (ClassLoader) cl;
                }
            }
            Method getCl = classLoaderData.getClass().getMethod("getClassLoader");
            Object cl = getCl.invoke(classLoaderData);
            if (cl instanceof ClassLoader) {
                return (ClassLoader) cl;
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    /**
     * 用 ClassLoaderData.reservedClassNames 经灵元 CL 重新解析生成类并清 MethodProxy。
     * <p>
     * JDK 17 无 ClassLoader.classes；LoadingCache 中的 WeakReference 可能已空，
     * 但增强类仍由 MethodProxy 静态字段等路径存活。reservedClassNames 仍持有类名字符串。
     */
    @SuppressWarnings("unchecked")
    private int clearMethodProxyViaReservedNames(String lingId,
                                                 Object classLoaderData,
                                                 ClassLoader lingCl,
                                                 Set<Class<?>> seen) {
        if (lingCl == null) {
            return 0;
        }
        try {
            Field namesField = classLoaderData.getClass().getDeclaredField("reservedClassNames");
            namesField.setAccessible(true);
            Object raw = namesField.get(classLoaderData);
            if (!(raw instanceof Collection<?>)) {
                return 0;
            }
            int cleaned = 0;
            int resolved = 0;
            for (Object nameObj : (Collection<?>) raw) {
                if (!(nameObj instanceof String)) {
                    continue;
                }
                String name = (String) nameObj;
                if (!isSpringCglibGenerated(name)) {
                    continue;
                }
                try {
                    Class<?> clazz = Class.forName(name, false, lingCl);
                    if (!seen.add(clazz)) {
                        continue;
                    }
                    // 共享边界防误伤：CGLIB 命名确定，灵元侧与灵核侧会复用同名增强类；
                    // forName 经父委派可能解析到灵核定义的共享类，它持有的是灵核自己的
                    // beanFactory/策略，清掉其 createInfo 会令灵核后续上下文全部
                    // MethodProxy.init NPE。只清灵元 CL 定义的类。
                    if (clazz.getClassLoader() != lingCl) {
                        log.debug("[{}] Skip shared core-side CGLIB class {}: defined by {}",
                                lingId, name, clazz.getClassLoader());
                        continue;
                    }
                    resolved++;
                    cleaned += clearMethodProxyStaticFields(lingId, clazz);
                } catch (ClassNotFoundException ignored) {
                    // 类已不可解析，跳过
                } catch (Throwable t) {
                    log.trace("[{}] reservedClassNames forName failed for {}: {}", lingId, name, t.getMessage());
                }
            }
            if (resolved > 0 || cleaned > 0) {
                log.info("[{}] reservedClassNames MethodProxy cleanup: resolved={}, MethodProxyFields={}",
                        lingId, resolved, cleaned);
            }
            return cleaned;
        } catch (NoSuchFieldException e) {
            log.trace("[{}] reservedClassNames not found: {}", lingId, e.getMessage());
            return 0;
        } catch (Exception e) {
            log.debug("[{}] reservedClassNames cleanup failed: {}", lingId, e.getMessage());
            return 0;
        }
    }

    /**
     * 从 LoadingCache value 提取生成 Class。
     * <p>
     * 可能形态：Class / WeakReference&lt;Class&gt; / SoftReference / FutureTask.result。
     */
    private Class<?> unwrapGeneratedClass(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Class<?>) {
            return (Class<?>) value;
        }
        if (value instanceof Reference<?>) {
            Object referent = ((Reference<?>) value).get();
            if (referent instanceof Class<?>) {
                return (Class<?>) referent;
            }
            // 嵌套再解一层（极少见）
            return unwrapGeneratedClass(referent);
        }
        // FutureTask / CompletableFuture 等：尝试 result 字段
        try {
            Field resultField = SpringCleanupSupport.findFieldInHierarchy(value.getClass(), "result");
            if (resultField != null) {
                resultField.setAccessible(true);
                return unwrapGeneratedClass(resultField.get(value));
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    /** 通用兜底：扫描 AbstractClassGenerator 所有静态 Map 字段并 remove CL key */
    private void clearCglibCacheGeneric(String lingId, ClassLoader lingClassLoader) {
        try {
            Class<?> c = Class.forName(ABSTRACT_CLASS_GENERATOR);
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (!Map.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Map<?, ?> map = (Map<?, ?>) f.get(null);
                    if (map == null) {
                        continue;
                    }
                    // 若 value 是 ClassLoaderData，先尝试抽 MethodProxy
                    Object data = map.get(lingClassLoader);
                    if (data != null && data.getClass().getName().contains("ClassLoaderData")) {
                        clearMethodProxyFromClassLoaderData(lingId, data);
                    }
                    Object removed = map.remove(lingClassLoader);
                    if (removed != null) {
                        log.info("[{}] Cleared CGLIB cache field '{}' (generic)", lingId, f.getName());
                    }
                } catch (Exception e) {
                    log.trace("[{}] Failed to clear CGLIB field {}: {}", lingId, f.getName(), e.getMessage());
                }
            }
        } catch (ClassNotFoundException e) {
            log.trace("[{}] CGLIB not on classpath", lingId);
        } catch (Exception e) {
            log.debug("[{}] CGLIB generic cache cleanup failed: {}", lingId, e.getMessage());
        }
    }

    /**
     * 通过 ClassLoader.classes 扫描 CGLIB 增强类（JDK 8 可用；JDK 17+ 字段不存在则跳过）。
     */
    @SuppressWarnings("unchecked")
    private void clearMethodProxyViaClassLoaderClasses(String lingId, ClassLoader lingClassLoader) {
        try {
            Field classesField = ClassLoader.class.getDeclaredField("classes");
            classesField.setAccessible(true);
            Vector<Class<?>> classes = (Vector<Class<?>>) classesField.get(lingClassLoader);
            if (classes == null) {
                return;
            }
            int enhancedCount = 0;
            int cleanedTotal = 0;
            for (Class<?> clazz : classes.toArray(new Class<?>[0])) {
                String name = clazz.getName();
                if (isSpringCglibGenerated(name)) {
                    enhancedCount++;
                    cleanedTotal += clearMethodProxyStaticFields(lingId, clazz);
                }
            }
            if (enhancedCount > 0 || cleanedTotal > 0) {
                log.info("[{}] ClassLoader.classes scan: size={}, enhanced={}, MethodProxy cleaned={}",
                        lingId, classes.size(), enhancedCount, cleanedTotal);
            }
        } catch (NoSuchFieldException e) {
            // JDK 17+：ClassLoader.classes 已移除，预期行为，勿打 INFO 噪音
            log.trace("[{}] ClassLoader.classes not available (JDK 9+): {}", lingId, e.getMessage());
        } catch (Exception e) {
            log.debug("[{}] ClassLoader.classes scan failed: {}", lingId, e.getMessage());
        }
    }

    static boolean isSpringCglibGenerated(String className) {
        if (className == null) {
            return false;
        }
        return className.contains(SPRING6_CGLIB_MARKER)
                || className.contains(SPRING5_ENHANCER_MARKER);
    }

    /**
     * 清空指定类上所有 MethodProxy 类型的静态字段，并断开 MethodProxy 内部 createInfo/fastClassInfo。
     *
     * @return 成功置 null 的 static 字段数（createInfo 清理次数不计入，仅日志）
     */
    private int clearMethodProxyStaticFields(String lingId, Class<?> enhancedClass) {
        int nulled = 0;
        int internalsCleared = 0;
        try {
            Field[] fields = enhancedClass.getDeclaredFields();
            for (Field f : fields) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Class<?> ft = f.getType();
                boolean isMethodProxy = METHOD_PROXY_CLASS_NAME.equals(ft.getName());
                // 引用类型静态字段全量清理（清 MethodProxy 之外漏掉的泄漏源）：
                //   - CGLIB$THREAD_CALLBACKS（静态 ThreadLocal 持 Callback[] → Enhancer → beanFactory → 灵元CL）
                //   - CGLIB$FACTORY_DATA / CGLIB$CALLBACK_* 等 CGLIB 内部引用
                // 只跳过基本类型与 String（无对象引用链）。
                if (!isMethodProxy && (ft.isPrimitive() || ft == String.class)) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (isMethodProxy && val != null) {
                        if (clearMethodProxyInternals(val)) {
                            internalsCleared++;
                        }
                    }
                    // 尽量把 static 字段置 null：普通字段反射设，final 字段 Unsafe 兜底（JDK 17 关键）
                    setStaticFieldNull(f, enhancedClass, enhancedClass.getName() + "." + f.getName());
                    nulled++;
                } catch (Exception e) {
                    log.debug("[{}] Failed to clear static field {} on {}: {}",
                            lingId, f.getName(), enhancedClass.getName(), e.getMessage());
                }
            }
            if (nulled > 0 || internalsCleared > 0) {
                log.info("[{}] {}: CGLIB static nulled={}, MethodProxyInternalsCleared={}",
                        lingId, enhancedClass.getName(), nulled, internalsCleared);
            }
        } catch (Exception e) {
            log.debug("[{}] Failed to scan static fields on {}: {}",
                    lingId, enhancedClass.getName(), e.getMessage());
        }
        return nulled;
    }

    /**
     * 清空 MethodProxy 内部 createInfo / fastClassInfo，断开
     * CreateInfo.strategy → BeanFactoryAwareGeneratorStrategy → beanFactory → 灵元CL。
     */
    private boolean clearMethodProxyInternals(Object methodProxy) {
        boolean cleared = false;
        cleared |= nullifyInstanceField(methodProxy, "createInfo");
        cleared |= nullifyInstanceField(methodProxy, "fastClassInfo");
        return cleared;
    }

    private boolean nullifyInstanceField(Object target, String fieldName) {
        try {
            Field field = SpringCleanupSupport.findFieldInHierarchy(target.getClass(), fieldName);
            if (field == null) {
                return false;
            }
            field.setAccessible(true);
            if (field.get(target) == null) {
                return false;
            }
            stripFinalModifierQuietly(field);
            field.set(target, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 尝试清除 Field 的 final 修饰符（JDK 8 有效；JDK 9+ 通常失败，调用方应吞掉异常）。
     */
    private void stripFinalModifierQuietly(Field field) {
        if (!Modifier.isFinal(field.getModifiers())) {
            return;
        }
        try {
            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
        } catch (Throwable ignored) {
            // JDK 9+ 模块限制，忽略
        }
    }
}
