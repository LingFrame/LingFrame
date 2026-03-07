package com.lingframe.starter.resource;

import com.lingframe.core.resource.BasicResourceGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.CachedIntrospectionResults;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.SpringVersion;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;

import com.lingframe.starter.spi.SpringAwareResourceGuard;

@Slf4j
public class SpringBasicResourceGuard extends BasicResourceGuard implements SpringAwareResourceGuard {

    /**
     * Spring Framework 主版本号
     * 5 = Spring Boot 2.x
     * 6 = Spring Boot 3.x
     */
    private static final int SPRING_MAJOR_VERSION = detectSpringMajorVersion();

    protected ApplicationContext mainContext;
    protected ConfigurableApplicationContext lingContext;

    private static int detectSpringMajorVersion() {
        try {
            String version = SpringVersion.getVersion();
            if (version != null && !version.isEmpty()) {
                int major = Integer.parseInt(version.split("\\.")[0]);
                log.info("Detected Spring Framework version: {} (major: {})", version, major);
                return major;
            }
        } catch (Exception e) {
            log.debug("Failed to detect Spring version: {}", e.getMessage());
        }
        return 5; // 默认 Spring 5
    }

    @Override
    public void setContexts(ApplicationContext mainContext,
            ConfigurableApplicationContext lingContext) {
        this.mainContext = mainContext;
        this.lingContext = lingContext;
    }

    // =========================================================================
    // 第一阶段：Context 活跃期预清理
    // =========================================================================

    @Override
    public void preCleanup(String lingId) {
        if (lingContext == null || !lingContext.isActive())
            return;

        ClassLoader lingClassLoader = lingContext.getClassLoader();
        log.info("[{}] Starting Spring pre-cleanup,target CL: {}@{}, Spring version: {}.x",
                lingId,
                lingClassLoader == null ? "null" : lingClassLoader.getClass().getSimpleName(),
                Integer.toHexString(System.identityHashCode(lingClassLoader)),
                SPRING_MAJOR_VERSION);

        try {
            ConfigurableListableBeanFactory beanFactory = lingContext.getBeanFactory();

            // 1. 关闭遗留的 ExecutorService
            shutdownExecutors(lingId, beanFactory);

            // 2. 清理 lifecycleMetadataCache
            clearLifecycleMetadataCache(lingId, beanFactory, lingClassLoader);

            // 3. 清理 Environment PropertySources
            cleanSpringEnvironment(lingId);

            // 4. 清理 EventMulticaster retrieverCache
            cleanRetrieverCache(lingId);

            // 5. 关闭 DataSource
            closeDataSources(lingId, beanFactory);

        } catch (Exception e) {
            log.debug("[{}] Spring pre-cleanup failed: {}", lingId, e.getMessage());
        }
    }

    private void cleanSpringEnvironment(String lingId) {
        try {
            Environment rawEnv = lingContext.getEnvironment();
            if (rawEnv instanceof ConfigurableEnvironment) {
                ConfigurableEnvironment env = (ConfigurableEnvironment) rawEnv;
                MutablePropertySources sources = env.getPropertySources();
                List<String> names = new ArrayList<>();
                sources.forEach(ps -> names.add(ps.getName()));
                names.forEach(sources::remove);
                log.info("[{}] Cleared {} PropertySources from ling Environment", lingId, names.size());
            }
        } catch (Exception e) {
            log.debug("[{}] Failed to clear PropertySources: {}", lingId, e.getMessage());
        }
    }

    private void cleanRetrieverCache(String lingId) {
        try {
            Object multicaster = lingContext
                    .getBean(AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME);
            if (multicaster == null)
                return;

            // 沿继承链查找 retrieverCache（兼容 Spring 5.x / 6.x）
            Field retrieverCacheField = findFieldInHierarchy(
                    multicaster.getClass(), "retrieverCache");

            if (retrieverCacheField != null) {
                retrieverCacheField.setAccessible(true);
                Object cache = retrieverCacheField.get(multicaster);
                if (cache instanceof Map<?, ?>) {
                    int size = ((Map<?, ?>) cache).size();
                    ((Map<?, ?>) cache).clear();
                    log.debug("[{}] Cleared retrieverCache ({} entries)", lingId, size);
                }
            } else {
                log.trace("[{}] retrieverCache field not found", lingId);
            }
        } catch (NoSuchBeanDefinitionException e) {
            log.trace("[{}] No ApplicationEventMulticaster bean found", lingId);
        } catch (Exception e) {
            log.debug("[{}] Failed to clear retrieverCache: {}", lingId, e.getMessage());
        }
    }

    private void closeDataSources(String lingId, ConfigurableListableBeanFactory beanFactory) {
        try {
            for (String name : beanFactory.getBeanNamesForType(
                    DataSource.class, true, false)) {
                Object ds = beanFactory.getSingleton(name);
                if (ds instanceof AutoCloseable) {
                    try {
                        ((AutoCloseable) ds).close();
                        log.info("[{}] Closed DataSource: {}", lingId, name);
                    } catch (Exception e) {
                        log.warn("[{}] Failed to close DataSource {}: {}", lingId, name, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[{}] DataSource cleanup failed: {}", lingId, e.getMessage());
        }
    }

    // =========================================================================
    // 第二阶段：cleanup 统一入口
    // =========================================================================

    @Override
    public void cleanup(String lingId, ClassLoader classLoader) {
        log.info("[{}] Starting Spring basic resource cleanup (Spring {}.x)...",
                lingId, SPRING_MAJOR_VERSION);

        // 1. Spring 框架缓存清理
        springCleanup(lingId, classLoader);

        // 2. 纯 Java 基础清理
        super.cleanup(lingId, classLoader);

        // 3. 释放 context 引用
        this.mainContext = null;
        this.lingContext = null;
    }

    @Override
    public void clearContexts() {
        this.mainContext = null;
        this.lingContext = null;
    }

    // =========================================================================
    // Spring 框架缓存清理
    // =========================================================================

    protected void springCleanup(String lingId, ClassLoader lingClassLoader) {
        // ---- 公开 API（所有版本稳定）----
        clearStablePublicCaches(lingId, lingClassLoader);

        // ---- 反射清理（需版本适配）----
        clearSpringFactoriesCache(lingId, lingClassLoader);
        clearSpringShutdownHook(lingId, lingClassLoader);
        clearCglibCache(lingId, lingClassLoader);
        clearObjenesisCache(lingId, lingClassLoader);
        clearELCache(lingId, lingClassLoader);
    }

    // ======================== 公开 API（稳定）========================

    private void clearStablePublicCaches(String lingId, ClassLoader lingClassLoader) {
        // 1. CachedIntrospectionResults — 所有版本都有的公开 API
        try {
            CachedIntrospectionResults.clearClassLoader(lingClassLoader);
            log.debug("[{}] Cleared CachedIntrospectionResults", lingId);
        } catch (Exception e) {
            log.debug("[{}] CachedIntrospectionResults cleanup failed: {}", lingId, e.getMessage());
        }

        // 2. ReflectionUtils
        clearReflectionUtilsSelective(lingId, lingClassLoader);

        // 3. AnnotationUtils
        clearAnnotationUtilsSelective(lingId, lingClassLoader);

        // 4. ResolvableType
        clearResolvableTypeSelective(lingId, lingClassLoader);

        // 5. JDK ResourceBundle
        try {
            ResourceBundle.clearCache(lingClassLoader);
            log.debug("[{}] Cleared ResourceBundle cache", lingId);
        } catch (Exception e) {
            log.debug("[{}] ResourceBundle cache cleanup failed: {}", lingId, e.getMessage());
        }
    }

    // ======================== ReflectionUtils 精确清理 ========================

    /**
     * ReflectionUtils 内部有两个缓存:
     * - declaredFieldsCache: Map<Class<?>, Field[]>
     * - declaredMethodsCache: Map<Class<?>, Method[]>
     * <p>
     * 只移除由目标 ClassLoader 加载的 Class 的条目
     */
    private void clearReflectionUtilsSelective(String lingId, ClassLoader lingClassLoader) {
        String[] cacheFieldNames = { "declaredFieldsCache", "declaredMethodsCache" };

        for (String fieldName : cacheFieldNames) {
            try {
                Field f = ReflectionUtils.findField(ReflectionUtils.class, fieldName);
                if (f == null)
                    continue;

                f.setAccessible(true);
                Map<?, ?> cache = (Map<?, ?>) f.get(null);
                if (cache == null)
                    continue;

                int before = cache.size();
                cache.entrySet().removeIf(entry -> {
                    Object key = entry.getKey();
                    return key instanceof Class<?>
                            && ((Class<?>) key).getClassLoader() == lingClassLoader;
                });

                int removed = before - cache.size();
                if (removed > 0) {
                    log.debug("[{}] ReflectionUtils.{}: removed {} entries", lingId, fieldName, removed);
                }
            } catch (Exception e) {
                log.trace("[{}] Failed to selectively clear ReflectionUtils.{}", lingId, fieldName);
            }
        }
    }

    // ======================== AnnotationUtils 精确清理 ========================

    /**
     * AnnotationUtils 内部缓存:
     * Spring 5.x: annotatedInterfaceCache, findAnnotationCache, ...
     * Spring 6.x: 可能合并或重命名
     * <p>
     * 通用策略：扫描所有静态 Map 字段
     */
    private void clearAnnotationUtilsSelective(String lingId, ClassLoader lingClassLoader) {
        try {
            int totalRemoved = 0;

            for (Field f : AnnotationUtils.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()))
                    continue;
                if (!Map.class.isAssignableFrom(f.getType()))
                    continue;

                try {
                    f.setAccessible(true);
                    Map<?, ?> cache = (Map<?, ?>) f.get(null);
                    if (cache == null || cache.isEmpty())
                        continue;

                    int before = cache.size();
                    cache.entrySet().removeIf(entry -> isRelatedToClassLoader(entry.getKey(), lingClassLoader));
                    totalRemoved += (before - cache.size());
                } catch (Exception ignored) {
                }
            }

            // AnnotatedElementUtils 也有缓存
            try {
                Class<?> aeClass = Class.forName(
                        "org.springframework.core.annotation.AnnotatedElementUtils");
                for (Field f : aeClass.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()))
                        continue;
                    if (!Map.class.isAssignableFrom(f.getType()))
                        continue;

                    try {
                        f.setAccessible(true);
                        Map<?, ?> cache = (Map<?, ?>) f.get(null);
                        if (cache == null)
                            continue;

                        int before = cache.size();
                        cache.entrySet().removeIf(entry -> isRelatedToClassLoader(entry.getKey(), lingClassLoader));
                        totalRemoved += (before - cache.size());
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }

            // MergedAnnotations 缓存（Spring 5.2+）
            try {
                Class<?> maClass = Class.forName(
                        "org.springframework.core.annotation.MergedAnnotationsCollection");
                removeStaticMapEntries(maClass, lingClassLoader);
            } catch (Exception ignored) {
            }

            try {
                Class<?> maClass = Class.forName(
                        "org.springframework.core.annotation.TypeMappedAnnotations");
                totalRemoved += removeStaticMapEntries(maClass, lingClassLoader);
            } catch (Exception ignored) {
            }

            try {
                Class<?> maClass = Class.forName(
                        "org.springframework.core.annotation.AnnotationTypeMappings");
                totalRemoved += removeStaticMapEntries(maClass, lingClassLoader);
            } catch (Exception ignored) {
            }

            if (totalRemoved > 0) {
                log.debug("[{}] AnnotationUtils selective cleanup: removed {} entries total",
                        lingId, totalRemoved);
            }
        } catch (Exception e) {
            log.debug("[{}] AnnotationUtils selective cleanup failed: {}", lingId, e.getMessage());
        }
    }

    // ======================== ResolvableType 精确清理 ========================

    /**
     * ResolvableType 内部有 cache = ConcurrentReferenceHashMap<ResolvableType,
     * ResolvableType>
     * key 和 value 都是 ResolvableType，内部持有 Class<?> type 字段
     */
    private void clearResolvableTypeSelective(String lingId, ClassLoader lingClassLoader) {
        try {
            Field cacheField = ReflectionUtils.findField(ResolvableType.class, "cache");
            if (cacheField == null) {
                // 尝试其他可能的名字
                for (Field f : ResolvableType.class.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())
                            && Map.class.isAssignableFrom(f.getType())) {
                        cacheField = f;
                        break;
                    }
                }
            }
            if (cacheField == null)
                return;

            cacheField.setAccessible(true);
            Map<?, ?> cache = (Map<?, ?>) cacheField.get(null);
            if (cache == null || cache.isEmpty())
                return;

            int before = cache.size();
            cache.entrySet().removeIf(entry -> {
                return isResolvableTypeRelated(entry.getKey(), lingClassLoader)
                        || isResolvableTypeRelated(entry.getValue(), lingClassLoader);
            });

            int removed = before - cache.size();
            if (removed > 0) {
                log.debug("[{}] ResolvableType cache: removed {} entries", lingId, removed);
            }
        } catch (Exception e) {
            log.debug("[{}] ResolvableType selective cleanup failed: {}", lingId, e.getMessage());
        }
    }

    /**
     * 检查 ResolvableType 是否关联目标 ClassLoader
     * ResolvableType 内部有 type（Class/Type）、resolved（Class）等字段
     */
    private boolean isResolvableTypeRelated(Object obj, ClassLoader cl) {
        if (obj == null)
            return false;

        try {
            // 尝试 resolve() 方法获取实际 Class
            Method resolveMethod = obj.getClass().getMethod("resolve");
            Object resolved = resolveMethod.invoke(obj);
            if (resolved instanceof Class<?>) {
                return ((Class<?>) resolved).getClassLoader() == cl;
            }
        } catch (Exception ignored) {
        }

        // 兜底：检查内部 type 字段
        try {
            Field typeField = findFieldInHierarchy(obj.getClass(), "type");
            if (typeField != null) {
                typeField.setAccessible(true);
                Object type = typeField.get(obj);
                if (type instanceof Class<?>) {
                    return ((Class<?>) type).getClassLoader() == cl;
                }
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    // ======================== SpringFactoriesLoader ========================

    protected void clearSpringFactoriesCache(String lingId, ClassLoader lingClassLoader) {
        // Spring 5.x: 静态字段 cache = Map<ClassLoader, Map<String, List<String>>>
        // Spring 6.x: 重构了，可能改成实例级缓存 / forDefaultResourceLocation
        // 通用策略：扫描所有静态 Map 字段，按 ClassLoader key 移除

        try {
            int cleared = 0;
            for (Field field : SpringFactoriesLoader.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()))
                    continue;
                if (!Map.class.isAssignableFrom(field.getType()))
                    continue;

                try {
                    field.setAccessible(true);
                    Map<?, ?> map = (Map<?, ?>) field.get(null);
                    if (map != null) {
                        Object removed = map.remove(lingClassLoader);
                        if (removed != null) {
                            cleared++;
                            log.debug("[{}] Cleared SpringFactoriesLoader.{}", lingId, field.getName());
                        }
                    }
                } catch (Exception e) {
                    log.trace("[{}] Failed to clear field {}: {}", lingId, field.getName(), e.getMessage());
                }
            }
            if (cleared > 0) {
                log.info("[{}] Cleared {} SpringFactoriesLoader cache entries", lingId, cleared);
            }
        } catch (Exception e) {
            log.debug("[{}] SpringFactoriesLoader cleanup failed: {}", lingId, e.getMessage());
        }
    }

    // ======================== ShutdownHook ========================

    protected void clearSpringShutdownHook(String lingId, ClassLoader lingClassLoader) {
        try {
            Class<?> hooksClass = Class.forName("java.lang.ApplicationShutdownHooks");
            Field hooksField = hooksClass.getDeclaredField("hooks");
            hooksField.setAccessible(true);
            @SuppressWarnings("unchecked")
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

    private boolean isSpringShutdownHook(Thread hook, ClassLoader lingClassLoader) {
        String name = hook.getName();
        String className = hook.getClass().getName();

        // 名字或类名包含 Spring 关键词
        boolean isSpringHook = name.contains("SpringApplicationShutdownHook")
                || className.contains("SpringApplicationShutdownHook")
                || className.contains("SpringContextShutdownHook");

        if (!isSpringHook)
            return false;

        // 确认是目标 ClassLoader 的
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
            // Java 21+ 可能没有 target 字段
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

    // ======================== CGLIB 缓存 ========================

    private void clearCglibCache(String lingId, ClassLoader lingClassLoader) {
        if (SPRING_MAJOR_VERSION >= 6) {
            clearCglibCacheV6(lingId, lingClassLoader);
        } else {
            clearCglibCacheV5(lingId, lingClassLoader);
        }
    }

    /**
     * Spring 5.x / Boot 2.x
     * AbstractClassGenerator 有一个 static CACHE 字段
     */
    private void clearCglibCacheV5(String lingId, ClassLoader lingClassLoader) {
        try {
            Class<?> c = Class.forName(
                    "org.springframework.cglib.core.AbstractClassGenerator");
            Field f = c.getDeclaredField("CACHE");
            f.setAccessible(true);
            Object cache = f.get(null);

            if (cache instanceof Map<?, ?>) {
                Map<?, ?> map = (Map<?, ?>) cache;
                Object removed = map.remove(lingClassLoader);
                if (removed != null) {
                    log.info("[{}] Cleared CGLIB CACHE entry (Spring 5.x)", lingId);
                }
            }
        } catch (NoSuchFieldException e) {
            // 可能不是预期的 Spring 5 结构，走兜底
            clearCglibCacheGeneric(lingId, lingClassLoader);
        } catch (Exception e) {
            log.debug("[{}] CGLIB V5 cache cleanup failed: {}", lingId, e.getMessage());
        }
    }

    /**
     * Spring 6.x / Boot 3.x
     * CGLIB 重构了缓存结构，改用 ClassLoaderData，存在 WeakHashMap 或类似结构
     */
    private void clearCglibCacheV6(String lingId, ClassLoader lingClassLoader) {
        clearCglibCacheGeneric(lingId, lingClassLoader);
    }

    /**
     * 通用兜底：扫描 AbstractClassGenerator 所有静态 Map 字段
     */
    private void clearCglibCacheGeneric(String lingId, ClassLoader lingClassLoader) {
        try {
            Class<?> c = Class.forName(
                    "org.springframework.cglib.core.AbstractClassGenerator");

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

    // ======================== Objenesis 缓存 ========================

    private void clearObjenesisCache(String lingId, ClassLoader lingClassLoader) {
        // 策略1：通过 ObjenesisCglibAopProxy.objenesis → cache
        boolean cleared = clearObjenesisViaProxy(lingId, lingClassLoader);

        // 策略2：直接找 SpringObjenesis 实例中的缓存
        if (!cleared) {
            clearObjenesisDirect(lingId, lingClassLoader);
        }

        // 策略3：BaseInstantiatorStrategy 缓存
        clearBaseInstantiatorCache(lingId, lingClassLoader);
    }

    private boolean clearObjenesisViaProxy(String lingId, ClassLoader lingClassLoader) {
        try {
            Class<?> proxyClass = Class.forName(
                    "org.springframework.aop.framework.ObjenesisCglibAopProxy");

            // 找 objenesis 字段（可能叫 objenesis 或其他名字）
            Field objenesisField = findStaticFieldByType(proxyClass, "org.springframework.objenesis.SpringObjenesis");
            if (objenesisField == null) {
                // 直接按名字找
                try {
                    objenesisField = proxyClass.getDeclaredField("objenesis");
                } catch (NoSuchFieldException e) {
                    return false;
                }
            }

            objenesisField.setAccessible(true);
            Object objenesis = objenesisField.get(null);
            if (objenesis == null)
                return false;

            return clearMapFieldsByClassLoaderKey(objenesis, lingClassLoader,
                    "[" + lingId + "] Objenesis");

        } catch (ClassNotFoundException e) {
            log.debug("[{}] ObjenesisCglibAopProxy not found (may be removed in this Spring version)", lingId);
            return false;
        } catch (Exception e) {
            log.debug("[{}] Objenesis via proxy cleanup failed: {}", lingId, e.getMessage());
            return false;
        }
    }

    private void clearObjenesisDirect(String lingId, ClassLoader lingClassLoader) {
        try {
            Class<?> c = Class.forName("org.springframework.objenesis.SpringObjenesis");
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()))
                    continue;
                if (!Map.class.isAssignableFrom(f.getType()))
                    continue;

                try {
                    f.setAccessible(true);
                    Map<?, ?> map = (Map<?, ?>) f.get(null);
                    if (map != null) {
                        removeByClassLoaderKey(map, lingClassLoader);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (ClassNotFoundException e) {
            log.trace("[{}] SpringObjenesis not on classpath", lingId);
        } catch (Exception e) {
            log.debug("[{}] Objenesis direct cleanup failed: {}", lingId, e.getMessage());
        }
    }

    private void clearBaseInstantiatorCache(String lingId, ClassLoader lingClassLoader) {
        String[] classNames = {
                "org.springframework.objenesis.strategy.BaseInstantiatorStrategy",
                "org.springframework.objenesis.strategy.StdInstantiatorStrategy"
        };
        String[] fieldNames = { "INSTANTIATOR_CACHE", "cache", "CACHE" };

        for (String className : classNames) {
            try {
                Class<?> c = Class.forName(className);
                for (String fieldName : fieldNames) {
                    try {
                        Field f = c.getDeclaredField(fieldName);
                        f.setAccessible(true);
                        Object cache = f.get(null);
                        if (cache instanceof Map<?, ?>) {
                            removeByClassLoaderKey((Map<?, ?>) cache, lingClassLoader);
                        }
                    } catch (NoSuchFieldException ignored) {
                    }
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Exception e) {
                log.trace("[{}] BaseInstantiator cleanup for {} failed", lingId, className);
            }
        }
    }

    // ======================== EL 缓存 ========================

    private void clearELCache(String lingId, ClassLoader lingClassLoader) {
        // Spring Boot 2.x → javax.el
        // Spring Boot 3.x → jakarta.el
        String[] elClassNames = {
                "jakarta.el.BeanELResolver",
                "javax.el.BeanELResolver"
        };

        for (String className : elClassNames) {
            try {
                Class<?> beanELResolverClass = ClassUtils.forName(className, lingClassLoader);
                Method purgeMethod = ReflectionUtils.findMethod(
                        beanELResolverClass, "purgeBeanClasses", ClassLoader.class);
                if (purgeMethod != null) {
                    purgeMethod.setAccessible(true);
                    purgeMethod.invoke(null, lingClassLoader);
                    log.debug("[{}] Cleared EL cache: {}", lingId, className);
                }
            } catch (ClassNotFoundException e) {
                // 正常：Boot 2.x 没有 jakarta，Boot 3.x 没有 javax
            } catch (Exception e) {
                log.debug("[{}] EL cache cleanup failed for {}: {}", lingId, className, e.getMessage());
            }
        }
    }

    // =========================================================================
    // 预清理辅助方法
    // =========================================================================

    private void shutdownExecutors(String lingId, ConfigurableListableBeanFactory beanFactory) {
        try {
            String[] names = beanFactory.getBeanNamesForType(ExecutorService.class);
            for (String name : names) {
                try {
                    ExecutorService executor = beanFactory.getBean(name, ExecutorService.class);
                    if (!executor.isShutdown()) {
                        executor.shutdownNow();
                        log.info("[{}] Shut down ExecutorService: {}", lingId, name);
                    }
                } catch (Exception e) {
                    log.debug("[{}] Failed to shutdown executor {}: {}", lingId, name, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("[{}] Executor cleanup failed: {}", lingId, e.getMessage());
        }
    }

    private void clearLifecycleMetadataCache(String lingId,
            ConfigurableListableBeanFactory beanFactory,
            ClassLoader lingClassLoader) {
        if (!(beanFactory instanceof DefaultListableBeanFactory))
            return;

        try {
            List<BeanPostProcessor> bpps = getBeanPostProcessors(beanFactory);
            if (bpps == null || bpps.isEmpty())
                return;

            String[] cacheFieldNames = {
                    "lifecycleMetadataCache",
                    "injectionMetadataCache",
                    "eligibleBeans"
            };

            for (BeanPostProcessor bpp : bpps) {
                for (String fieldName : cacheFieldNames) {
                    Field f = findFieldInHierarchy(bpp.getClass(), fieldName);
                    if (f == null)
                        continue;

                    try {
                        f.setAccessible(true);
                        Object cache = f.get(bpp);
                        if (!(cache instanceof Map<?, ?>))
                            continue;

                        Map<?, ?> map = (Map<?, ?>) cache;
                        int before = map.size();

                        // ✅ 精确移除：只移除目标 ClassLoader 相关的条目
                        map.entrySet().removeIf(entry -> {
                            Object key = entry.getKey();
                            // key 通常是 Class<?> 或 String(beanName)
                            if (key instanceof Class<?>) {
                                return ((Class<?>) key).getClassLoader() == lingClassLoader;
                            }
                            if (key instanceof String) {
                                // beanName 作为 key，检查 value
                                return isValueRelatedToClassLoader(entry.getValue(), lingClassLoader);
                            }
                            return isRelatedToClassLoader(key, lingClassLoader);
                        });

                        int removed = before - map.size();
                        if (removed > 0) {
                            log.debug("[{}] {}.{}: removed {} entries",
                                    lingId, bpp.getClass().getSimpleName(), fieldName, removed);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[{}] Lifecycle metadata cleanup failed: {}", lingId, e.getMessage());
        }
    }

    /**
     * 获取 BeanPostProcessor 列表
     * Spring 5.x: AbstractBeanFactory.beanPostProcessors 是 List
     * Spring 6.x: 可能封装成 BeanPostProcessorCache 内部类
     */
    @SuppressWarnings("unchecked")
    private List<BeanPostProcessor> getBeanPostProcessors(ConfigurableListableBeanFactory beanFactory) {
        String[] possibleFieldNames = { "beanPostProcessors", "beanPostProcessorCache" };

        for (String fieldName : possibleFieldNames) {
            Field f = findFieldInHierarchy(beanFactory.getClass(), fieldName);
            if (f == null)
                continue;

            try {
                f.setAccessible(true);
                Object val = f.get(beanFactory);

                // 直接是 List
                if (val instanceof List) {
                    return (List<BeanPostProcessor>) val;
                }

                // 可能是包装类（Spring 6.x BeanPostProcessorCache）
                if (val != null) {
                    // 尝试从包装类中提取所有 List<BeanPostProcessor> 字段
                    List<BeanPostProcessor> result = new ArrayList<>();
                    for (Field inner : val.getClass().getDeclaredFields()) {
                        if (List.class.isAssignableFrom(inner.getType())) {
                            try {
                                inner.setAccessible(true);
                                Object list = inner.get(val);
                                if (list instanceof List) {
                                    result.addAll((List<BeanPostProcessor>) list);
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    if (!result.isEmpty())
                        return result;
                }
            } catch (Exception ignored) {
            }
        }

        // 最后兜底：通过公开 API
        try {
            if (beanFactory instanceof DefaultListableBeanFactory) {
                String[] names = ((DefaultListableBeanFactory) beanFactory)
                        .getBeanNamesForType(BeanPostProcessor.class, true, false);
                List<BeanPostProcessor> result = new ArrayList<>();
                for (String name : names) {
                    try {
                        result.add(beanFactory.getBean(name, BeanPostProcessor.class));
                    } catch (Exception ignored) {
                    }
                }
                return result;
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    // =========================================================================
    // 通用工具方法
    // =========================================================================

    /**
     * 扫描类的所有静态 Map 字段，精确移除目标 ClassLoader 相关条目
     */
    private int removeStaticMapEntries(Class<?> clazz, ClassLoader cl) {
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

    /**
     * 检查 value 是否关联目标 ClassLoader
     * value 可能是 InjectionMetadata、LifecycleMetadata 等，内部持有 Class 引用
     */
    private boolean isValueRelatedToClassLoader(Object value, ClassLoader cl) {
        if (value == null)
            return false;

        // 直接检查 value 的 ClassLoader
        if (value.getClass().getClassLoader() == cl)
            return true;

        // 检查 value 内部的 targetClass / introspectedClass 字段
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

    /**
     * 从对象的所有 Map 类型字段中，移除 key 为目标 ClassLoader 加载的 Class 的条目
     */
    private boolean clearMapFieldsByClassLoaderKey(Object obj, ClassLoader cl, String logPrefix) {
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
                    log.info("{} cleared field '{}': {} → {} entries",
                            logPrefix, f.getName(), before, after);
                    anyCleared = true;
                }
            } catch (Exception e) {
                log.trace("{} failed to clear field '{}': {}", logPrefix, f.getName(), e.getMessage());
            }
        }
        return anyCleared;
    }

    /**
     * 从 Map 中精确移除目标 ClassLoader 相关条目
     */
    private int removeByClassLoaderKey(Map<?, ?> map, ClassLoader cl) {
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

    /**
     * 在类中查找指定类型的 static 字段
     */
    private Field findStaticFieldByType(Class<?> clazz, String typeName) {
        for (Field f : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())
                    && f.getType().getName().equals(typeName)) {
                return f;
            }
        }
        return null;
    }

    // =========================================================================
    // 公共工具方法（供子类使用）
    // =========================================================================

    protected static Field findFieldInHierarchy(Class<?> clazz, String fieldName) {
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

    protected static boolean isRelatedToClassLoader(Object obj, ClassLoader targetCL) {
        if (obj == null || targetCL == null)
            return false;
        if (obj instanceof Class<?>)
            return ((Class<?>) obj).getClassLoader() == targetCL;
        if (obj.getClass().getClassLoader() == targetCL)
            return true;
        return checkMethodClassKey(obj, targetCL);
    }

    protected static boolean checkMethodClassKey(Object key, ClassLoader targetCL) {
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

    protected static boolean isTargetClassLoader(ClassLoader cl, ClassLoader target) {
        ClassLoader current = cl;
        while (current != null) {
            if (current == target)
                return true;
            current = current.getParent();
        }
        return false;
    }
}