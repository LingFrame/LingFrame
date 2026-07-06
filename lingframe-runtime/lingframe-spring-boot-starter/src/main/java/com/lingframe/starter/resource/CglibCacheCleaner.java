package com.lingframe.starter.resource;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Vector;

/**
 * 清理 Spring CGLIB AbstractClassGenerator 静态缓存。
 * <p>
 * Spring 5.x：AbstractClassGenerator.CACHE 是 Map<ClassLoader, ClassLoaderData>；
 * Spring 6.x：缓存结构已重构，走通用兜底扫描。
 * <p>
 * 除了清理 CACHE 外，还需要清理 CGLIB 增强类的 MethodProxy 静态字段。
 * 增强类的 static MethodProxy 字段持有 MethodProxy → CreateInfo →
 * BeanFactoryAwareGeneratorStrategy → beanFactory → 灵元CL 的强引用链，
 * 与 CL → classes → 增强类 形成循环引用。BeanFactoryAwareGeneratorStrategy 是
 * 宿主CL 对象（spring-core），其 beanFactory 字段强引用灵元CL，构成外部强引用。
 * 清空 MethodProxy 静态字段可断开此引用链。
 */
@Slf4j
final class CglibCacheCleaner {

    /** CGLIB 增强类类名标识 */
    private static final String CGLIB_ENHANCER_MARKER = "$$EnhancerBySpringCGLIB$$";

    /** Spring CGLIB MethodProxy 全限定类名 */
    private static final String METHOD_PROXY_CLASS_NAME =
            "org.springframework.cglib.proxy.MethodProxy";

    private final int springMajorVersion;

    CglibCacheCleaner(int springMajorVersion) {
        this.springMajorVersion = springMajorVersion;
    }

    void clear(String lingId, ClassLoader lingClassLoader) {
        if (springMajorVersion >= 6) {
            clearCglibCacheGeneric(lingId, lingClassLoader);
        } else {
            clearCglibCacheV5(lingId, lingClassLoader);
        }
        // 兜底：通过 ClassLoader.classes 扫描所有 CGLIB 增强类，清理 MethodProxy 静态字段。
        // CACHE 中可能不包含所有增强类（如已被其他 Cleaner 提前移除），
        // 通过 CL.classes 可确保覆盖所有由灵元CL 加载的 CGLIB 增强类。
        clearMethodProxyViaClassLoaderClasses(lingId, lingClassLoader);
    }

    /** 面向 Spring 5.x / Boot 2.x：AbstractClassGenerator.CACHE 静态字段 */
    private void clearCglibCacheV5(String lingId, ClassLoader lingClassLoader) {
        try {
            Class<?> c = Class.forName("org.springframework.cglib.core.AbstractClassGenerator");
            Field f = c.getDeclaredField("CACHE");
            f.setAccessible(true);
            Object cache = f.get(null);
            if (cache instanceof Map<?, ?>) {
                Map<?, ?> map = (Map<?, ?>) cache;
                // Spring 5.x：CACHE 值是 ClassLoaderData，不是 Map。
                // 先从 ClassLoaderData 提取生成类清理 MethodProxy，再移除 CACHE 条目。
                Object classLoaderData = map.get(lingClassLoader);
                if (classLoaderData != null) {
                    clearMethodProxyFromClassLoaderData(lingId, classLoaderData);
                }
                Object removed = map.remove(lingClassLoader);
                if (removed != null) {
                    log.info("[{}] Cleared CGLIB CACHE entry (Spring 5.x)", lingId);
                }
            }
        } catch (NoSuchFieldException e) {
            // 不是预期的 Spring 5 结构，走兜底
            clearCglibCacheGeneric(lingId, lingClassLoader);
        } catch (Exception e) {
            log.debug("[{}] CGLIB V5 cache cleanup failed: {}", lingId, e.getMessage());
        }
    }

    /**
     * 从 Spring 5.x ClassLoaderData 提取已生成的 CGLIB 增强类，清理 MethodProxy 静态字段。
     * <p>
     * ClassLoaderData 内部持有 LoadingCache，其底层 ConcurrentMap 的 values 为
     * CGLIB 生成的增强类 Class 对象（或包装在 Future 中）。
     */
    @SuppressWarnings("unchecked")
    private void clearMethodProxyFromClassLoaderData(String lingId, Object classLoaderData) {
        try {
            Field generatedClassesField = classLoaderData.getClass().getDeclaredField("generatedClasses");
            generatedClassesField.setAccessible(true);
            Object loadingCache = generatedClassesField.get(classLoaderData);
            if (loadingCache == null) {
                return;
            }
            Field mapField = loadingCache.getClass().getDeclaredField("map");
            mapField.setAccessible(true);
            Map<?, ?> map = (Map<?, ?>) mapField.get(loadingCache);
            if (map == null || map.isEmpty()) {
                return;
            }
            int cleaned = 0;
            for (Object value : map.values()) {
                Class<?> generatedClass = unwrapGeneratedClass(value);
                if (generatedClass != null) {
                    cleaned += clearMethodProxyStaticFields(lingId, generatedClass);
                }
            }
            if (cleaned > 0) {
                log.info("[{}] Cleared {} MethodProxy static fields from ClassLoaderData", lingId, cleaned);
            }
        } catch (NoSuchFieldException e) {
            log.debug("[{}] ClassLoaderData structure not as expected: {}", lingId, e.getMessage());
        } catch (Exception e) {
            log.debug("[{}] Failed to extract classes from ClassLoaderData: {}", lingId, e.getMessage());
        }
    }

    /**
     * 从 LoadingCache 的 value 中提取生成的 Class。
     * value 可能是 Class 本身，也可能包装在 FutureTask 中。
     */
    private Class<?> unwrapGeneratedClass(Object value) {
        if (value instanceof Class<?>) {
            return (Class<?>) value;
        }
        // LoadingCache 可能将值包装在 FutureTask 中
        if (value != null) {
            try {
                Field resultField = null;
                Class<?> clazz = value.getClass();
                while (clazz != null && resultField == null) {
                    try {
                        resultField = clazz.getDeclaredField("result");
                        break;
                    } catch (NoSuchFieldException ignored) {
                        clazz = clazz.getSuperclass();
                    }
                }
                if (resultField != null) {
                    resultField.setAccessible(true);
                    Object result = resultField.get(value);
                    if (result instanceof Class<?>) {
                        return (Class<?>) result;
                    }
                }
            } catch (Exception ignored) {
                // 忽略，返回 null
            }
        }
        return null;
    }

    /** 通用兜底：扫描 AbstractClassGenerator 所有静态 Map 字段 */
    private void clearCglibCacheGeneric(String lingId, ClassLoader lingClassLoader) {
        try {
            Class<?> c = Class.forName("org.springframework.cglib.core.AbstractClassGenerator");
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()))
                    continue;
                if (!Map.class.isAssignableFrom(f.getType()))
                    continue;
                try {
                    f.setAccessible(true);
                    Map<?, ?> map = (Map<?, ?>) f.get(null);
                    if (map != null) {
                        Object removed = map.remove(lingClassLoader);
                        if (removed != null) {
                            log.info("[{}] Cleared CGLIB cache field '{}' (generic)", lingId, f.getName());
                        }
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
     * 通过 ClassLoader.classes 扫描 CGLIB 增强类，清理 MethodProxy 静态字段。
     * <p>
     * 当 CACHE 条目已被移除或不存在时，通过 CL.classes 仍可找到增强类。
     * CL.classes 是 JVM 维护的 Vector<Class<?>>，包含该 CL 加载的所有类。
     * 过滤类名含 {@code $$EnhancerBySpringCGLIB$$} 的增强类进行处理。
     */
    @SuppressWarnings("unchecked")
    private void clearMethodProxyViaClassLoaderClasses(String lingId, ClassLoader lingClassLoader) {
        try {
            Field classesField = ClassLoader.class.getDeclaredField("classes");
            classesField.setAccessible(true);
            Vector<Class<?>> classes = (Vector<Class<?>>) classesField.get(lingClassLoader);
            if (classes == null) {
                log.info("[{}] ClassLoader.classes is null", lingId);
                return;
            }
            int enhancedCount = 0;
            int cleanedTotal = 0;
            // 复制一份避免并发修改
            for (Class<?> clazz : classes.toArray(new Class<?>[0])) {
                String name = clazz.getName();
                if (name != null && name.contains(CGLIB_ENHANCER_MARKER)) {
                    enhancedCount++;
                    cleanedTotal += clearMethodProxyStaticFields(lingId, clazz);
                }
            }
            log.info("[{}] ClassLoader.classes scan: size={}, enhanced={}, MethodProxy cleaned={}",
                    lingId, classes.size(), enhancedCount, cleanedTotal);
        } catch (NoSuchFieldException e) {
            log.info("[{}] ClassLoader.classes field not accessible: {}", lingId, e.getMessage());
        } catch (Exception e) {
            log.info("[{}] ClassLoader.classes scan failed: {}", lingId, e.getMessage());
        }
    }

    /**
     * 清空指定类上所有 MethodProxy 类型的静态字段。
     * <p>
     * CGLIB 为每个被拦截的方法生成一个 static MethodProxy 字段（命名如 CGLIB$xxx$0），
     * MethodProxy 内部的 CreateInfo 持有生成策略（BeanFactoryAwareGeneratorStrategy），
     * 策略的 beanFactory 字段强引用灵元CL，形成泄漏环。
     * 将这些字段置 null 可断开引用链。
     *
     * @return 清理的字段数
     */
    private int clearMethodProxyStaticFields(String lingId, Class<?> enhancedClass) {
        int count = 0;
        int staticCount = 0;
        int matchCount = 0;
        try {
            Field[] fields = enhancedClass.getDeclaredFields();
            for (Field f : fields) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                staticCount++;
                // 用类名匹配，避免跨 ClassLoader 加载 MethodProxy 类
                String fieldTypeName = f.getType().getName();
                if (METHOD_PROXY_CLASS_NAME.equals(fieldTypeName)) {
                    matchCount++;
                    try {
                        f.setAccessible(true);
                        // CGLIB 生成的 MethodProxy 字段是 static final，JDK 8 下 f.set 会抛
                        // IllegalAccessException，需要先清除 modifiers 的 FINAL 位才能修改。
                        stripFinalModifier(f);
                        f.set(null, null);
                        count++;
                    } catch (Exception e) {
                        log.info("[{}] Failed to nullify MethodProxy field {} on {}: {}",
                                lingId, f.getName(), enhancedClass.getName(), e.getMessage());
                    }
                }
            }
            log.info("[{}] {}: fields={}, static={}, MethodProxy matched={}, cleaned={}",
                    lingId, enhancedClass.getName(), fields.length, staticCount, matchCount, count);
        } catch (Exception e) {
            log.info("[{}] Failed to scan MethodProxy fields on {}: {}",
                    lingId, enhancedClass.getName(), e.getMessage());
        }
        return count;
    }

    /**
     * 清除指定 Field 的 final 修饰符。
     * <p>
     * CGLIB 增强类的 MethodProxy 静态字段被声明为 static final，JDK 8 下直接 set(null) 会抛
     * IllegalAccessException。通过反射修改 Field.modifiers 清除 FINAL 位后即可写入。
     * <p>
     * 仅适用于 JDK 8（JDK 9+ 模块系统限制反射访问 java.lang.reflect.Field.modifiers）。
     */
    private void stripFinalModifier(Field field) throws ReflectiveOperationException {
        if (!Modifier.isFinal(field.getModifiers())) {
            return;
        }
        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
    }
}
