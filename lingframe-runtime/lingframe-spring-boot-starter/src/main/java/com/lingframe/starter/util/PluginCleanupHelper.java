package com.lingframe.starter.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.CachedIntrospectionResults;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ConfigurableApplicationContext;

import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.beans.Introspector;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * 插件 ClassLoader 泄漏清理工具
 * <p>
 * Spring 框架中存在大量静态缓存，它们不是为"类被卸载"的场景设计的。
 * 插件卸载时必须手动清理这些缓存，否则 PluginClassLoader 永远无法被 GC 回收。
 * <p>
 * 已知需要清理的缓存：
 * - SpringFactoriesLoader.cache
 * - BridgeMethodResolver.cache
 * - LiveBeansView.applicationContexts
 * - SpringApplicationShutdownHook.contexts
 * - OnAvailableEndpointCondition.exposureFiltersCache
 * - AnnotationUtils / AnnotatedElementUtils 内部缓存
 * - ReflectionUtils.declaredMethodsCache
 * - ResolvableType.cache
 * - SerializableTypeWrapper.cache
 * - CachedIntrospectionResults
 * - AbstractClassGenerator.CACHE (CGLIB)
 * - ApplicationAvailabilityBean.events
 * - RequestMappingHandlerAdapter 多个缓存
 * - DefaultListableBeanFactory.mergedBeanDefinitions
 */
@Slf4j
public class PluginCleanupHelper {

    // ==================== 已知需要清理的 Spring 核心类 ====================

    private static final List<Class<?>> SPRING_CORE_CLASSES = Arrays.asList(
            SpringFactoriesLoader.class,
            BridgeMethodResolver.class,
            AnnotationUtils.class,
            AnnotatedElementUtils.class,
            ReflectionUtils.class,
            ResolvableType.class,
            CachedIntrospectionResults.class);

    // Actuator / CGLIB 等可选类（可能不在 classpath 中）
    private static final List<String> OPTIONAL_CLASS_NAMES = Arrays.asList(
            "org.springframework.boot.actuate.autoconfigure.endpoint.condition.OnAvailableEndpointCondition",
            "org.springframework.cglib.core.AbstractClassGenerator",
            "org.springframework.boot.autoconfigure.condition.OnBeanCondition",
            "org.springframework.boot.autoconfigure.condition.OnClassCondition",
            "org.springframework.boot.autoconfigure.condition.OnWebApplicationCondition",
            "org.springframework.core.annotation.AnnotationsScanner",
            "org.springframework.core.annotation.RepeatableContainers",
            "org.springframework.core.annotation.TypeMappedAnnotations");

    // ==================== 公共入口 ====================

    /**
     * 插件 Context 关闭前调用
     * 清理需要在 Context 存活时处理的引用
     */
    public static void preCloseCleanup(ConfigurableApplicationContext pluginContext) {
        if (pluginContext == null)
            return;
        log.info("🧹 [Cleanup] Pre-close cleanup for plugin context");

        clearShutdownHook(pluginContext);
        clearLiveBeansView(pluginContext);
    }

    /**
     * 插件 Context 关闭后调用
     * 清理所有静态缓存和宿主引用
     */
    public static void postCloseCleanup(ClassLoader pluginClassLoader,
            ConfigurableApplicationContext hostContext,
            RequestMappingHandlerAdapter hostAdapter) {
        if (pluginClassLoader == null)
            return;
        log.info("🧹 [Cleanup] Post-close cleanup for ClassLoader: {}", pluginClassLoader);

        // 1. 通用扫荡：清理所有已知 Spring 类的静态 Map 缓存
        clearStaticCachesForKnownClasses(pluginClassLoader);

        // 2. 宿主 BeanFactory 缓存
        if (hostContext != null) {
            clearMergedBeanDefinitions(hostContext, pluginClassLoader);
            clearAvailabilityEvents(hostContext, pluginClassLoader);
            clearSingletonBeanRegistry(hostContext, pluginClassLoader);
        }

        // 3. HandlerAdapter 缓存
        if (hostAdapter != null) {
            clearAdapterCaches(hostAdapter, pluginClassLoader);
        }

        // 4. Java 内省缓存
        clearIntrospectionCaches(pluginClassLoader);

        // 5. 关闭 ClassLoader
        closeClassLoader(pluginClassLoader);

        log.info("🧹 [Cleanup] Cleanup complete for ClassLoader: {}", pluginClassLoader);
    }

    // ==================== 通用扫荡 ====================

    /**
     * 扫描所有已知 Spring 核心类的静态 Map/Set/Collection 字段
     * 移除与插件 ClassLoader 关联的条目
     */
    private static void clearStaticCachesForKnownClasses(ClassLoader pluginClassLoader) {
        // 已知必须存在的类
        for (Class<?> clazz : SPRING_CORE_CLASSES) {
            clearStaticFieldsOfClass(clazz, pluginClassLoader);
        }

        // 可选类（可能不在 classpath 中）
        for (String className : OPTIONAL_CLASS_NAMES) {
            try {
                Class<?> clazz = Class.forName(className);
                clearStaticFieldsOfClass(clazz, pluginClassLoader);
            } catch (ClassNotFoundException ignored) {
                // 类不存在，跳过
            }
        }
    }

    /**
     * 清理指定类的所有静态 Map/Set 字段中与插件 ClassLoader 关联的条目
     */
    private static void clearStaticFieldsOfClass(Class<?> targetClass, ClassLoader pluginClassLoader) {
        try {
            // 遍历本类和父类的所有字段
            Class<?> current = targetClass;
            while (current != null && current != Object.class) {
                for (Field field : current.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()))
                        continue;

                    try {
                        ReflectionUtils.makeAccessible(field);
                        Object value = field.get(null);

                        if (value instanceof Map) {
                            clearMap((Map<?, ?>) value, field, targetClass, pluginClassLoader);
                        } else if (value instanceof Set) {
                            clearSet((Set<?>) value, field, targetClass, pluginClassLoader);
                        } else if (value instanceof Collection) {
                            clearCollection((Collection<?>) value, field, targetClass, pluginClassLoader);
                        }
                    } catch (Exception e) {
                        log.trace("Skip field {}.{}: {}", targetClass.getSimpleName(),
                                field.getName(), e.getMessage());
                    }
                }
                current = current.getSuperclass();
            }
        } catch (Exception e) {
            log.debug("Failed to clear static fields of {}: {}", targetClass.getSimpleName(), e.getMessage());
        }
    }

    /**
     * 清理 Map 中与插件 ClassLoader 关联的条目
     */
    private static void clearMap(Map<?, ?> map, Field field, Class<?> ownerClass,
            ClassLoader pluginClassLoader) {
        if (map.isEmpty())
            return;

        try {
            int before = map.size();
            map.entrySet().removeIf(entry -> isRelatedToClassLoader(entry.getKey(), pluginClassLoader) ||
                    isRelatedToClassLoader(entry.getValue(), pluginClassLoader));
            int removed = before - map.size();
            if (removed > 0) {
                log.info("🧹 [Cleanup] {}.{}: removed {} entries",
                        ownerClass.getSimpleName(), field.getName(), removed);
            }
        } catch (UnsupportedOperationException e) {
            log.trace("Map {}.{} is unmodifiable", ownerClass.getSimpleName(), field.getName());
        }
    }

    /**
     * 清理 Set 中与插件 ClassLoader 关联的条目
     */
    private static void clearSet(Set<?> set, Field field, Class<?> ownerClass,
            ClassLoader pluginClassLoader) {
        if (set.isEmpty())
            return;

        try {
            int before = set.size();
            set.removeIf(item -> isRelatedToClassLoader(item, pluginClassLoader));
            int removed = before - set.size();
            if (removed > 0) {
                log.info("🧹 [Cleanup] {}.{}: removed {} entries",
                        ownerClass.getSimpleName(), field.getName(), removed);
            }
        } catch (UnsupportedOperationException e) {
            log.trace("Set {}.{} is unmodifiable", ownerClass.getSimpleName(), field.getName());
        }
    }

    /**
     * 清理 Collection 中与插件 ClassLoader 关联的条目
     */
    private static void clearCollection(Collection<?> collection, Field field, Class<?> ownerClass,
            ClassLoader pluginClassLoader) {
        if (collection.isEmpty())
            return;

        try {
            int before = collection.size();
            collection.removeIf(item -> isRelatedToClassLoader(item, pluginClassLoader));
            int removed = before - collection.size();
            if (removed > 0) {
                log.info("🧹 [Cleanup] {}.{}: removed {} entries",
                        ownerClass.getSimpleName(), field.getName(), removed);
            }
        } catch (UnsupportedOperationException e) {
            log.trace("Collection {}.{} is unmodifiable", ownerClass.getSimpleName(), field.getName());
        }
    }

    // ==================== 关联判断 ====================

    /**
     * 递归判断对象是否关联到指定 ClassLoader
     */
    private static boolean isRelatedToClassLoader(Object obj, ClassLoader targetCL) {
        if (obj == null || targetCL == null)
            return false;

        try {
            // 1. 对象本身由插件 ClassLoader 加载
            if (obj.getClass().getClassLoader() == targetCL)
                return true;

            // 2. 是 ClassLoader 本身
            if (obj instanceof ClassLoader) {
                return obj == targetCL || isChildClassLoader((ClassLoader) obj, targetCL);
            }

            // 3. 是 Class 对象
            if (obj instanceof Class<?>) {
                return ((Class<?>) obj).getClassLoader() == targetCL;
            }

            // 4. 是 Method 对象
            if (obj instanceof Method) {
                return ((Method) obj).getDeclaringClass().getClassLoader() == targetCL;
            }

            // 5. 是 java.lang.reflect.Field 对象
            if (obj instanceof Field) {
                return ((Field) obj).getDeclaringClass().getClassLoader() == targetCL;
            }

            // 6. 是 ApplicationContext
            if (obj instanceof ApplicationContext) {
                ClassLoader cl = ((ApplicationContext) obj).getClassLoader();
                return cl == targetCL;
            }

            // 7. 是 ApplicationEvent（source 可能是插件 Context）
            if (obj instanceof ApplicationEvent) {
                Object source = ((ApplicationEvent) obj).getSource();
                return isRelatedToClassLoader(source, targetCL);
            }

            // 8. 是 ClassPathResource
            if (obj instanceof ClassPathResource) {
                try {
                    Field clField = ClassPathResource.class.getDeclaredField("classLoader");
                    clField.setAccessible(true);
                    return clField.get(obj) == targetCL;
                } catch (Exception ignored) {
                }
            }

            // 9. 是 Environment（内部的 PropertySource 可能引用插件 ClassLoader）
            if (obj instanceof org.springframework.core.env.ConfigurableEnvironment) {
                return isEnvironmentRelated(
                        (org.springframework.core.env.ConfigurableEnvironment) obj, targetCL);
            }

        } catch (Exception e) {
            log.trace("Error checking ClassLoader relation: {}", e.getMessage());
        }

        return false;
    }

    /**
     * 检查 ClassLoader 是否是目标的子加载器
     */
    private static boolean isChildClassLoader(ClassLoader child, ClassLoader target) {
        ClassLoader current = child;
        while (current != null) {
            if (current == target)
                return true;
            current = current.getParent();
        }
        return false;
    }

    /**
     * 检查 Environment 是否关联到插件 ClassLoader
     */
    private static boolean isEnvironmentRelated(
            org.springframework.core.env.ConfigurableEnvironment env, ClassLoader targetCL) {
        try {
            for (org.springframework.core.env.PropertySource<?> ps : env.getPropertySources()) {
                if (ps.getClass().getClassLoader() == targetCL)
                    return true;
                Object source = ps.getSource();
                if (source != null && source.getClass().getClassLoader() == targetCL)
                    return true;

                // 检查 PropertySource 内部的 Resource 对象
                if (source instanceof Map) {
                    for (Object value : ((Map<?, ?>) source).values()) {
                        if (isRelatedToClassLoader(value, targetCL))
                            return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ==================== ShutdownHook 清理 ====================

    /**
     * 从 SpringApplicationShutdownHook 中移除插件 Context
     */
    private static void clearShutdownHook(ConfigurableApplicationContext pluginContext) {
        try {
            // Spring Boot 2.x/3.x: SpringApplication 有静态的 shutdownHook
            Field hookField = ReflectionUtils.findField(
                    org.springframework.boot.SpringApplication.class, "shutdownHook");
            if (hookField != null) {
                ReflectionUtils.makeAccessible(hookField);
                Object shutdownHook = ReflectionUtils.getField(hookField, null);

                if (shutdownHook != null) {
                    // 查找 contexts 字段
                    Field contextsField = ReflectionUtils.findField(
                            shutdownHook.getClass(), "contexts");
                    if (contextsField != null) {
                        ReflectionUtils.makeAccessible(contextsField);
                        @SuppressWarnings("unchecked")
                        Collection<ConfigurableApplicationContext> contexts = (Collection<ConfigurableApplicationContext>) ReflectionUtils
                                .getField(contextsField, shutdownHook);
                        if (contexts != null) {
                            boolean removed = contexts.remove(pluginContext);
                            if (removed) {
                                log.info("🧹 [Cleanup] Removed plugin context from ShutdownHook");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to clear ShutdownHook: {}", e.getMessage());
        }
    }

    // ==================== LiveBeansView 清理 ====================

    /**
     * 从 LiveBeansView.applicationContexts 静态集合中移除插件 Context
     * 注意：LiveBeansView 在 Spring 6.x (Boot 3) 中已被移除，此处用反射按类名加载
     */
    private static void clearLiveBeansView(ConfigurableApplicationContext pluginContext) {
        try {
            Class<?> liveBeansViewClass = Class.forName(
                    "org.springframework.context.support.LiveBeansView");
            Field contextsField = ReflectionUtils.findField(
                    liveBeansViewClass, "applicationContexts");
            if (contextsField != null) {
                ReflectionUtils.makeAccessible(contextsField);
                @SuppressWarnings("unchecked")
                Set<ConfigurableApplicationContext> contexts = (Set<ConfigurableApplicationContext>) ReflectionUtils
                        .getField(contextsField, null);
                if (contexts != null) {
                    boolean removed = contexts.remove(pluginContext);
                    if (removed) {
                        log.info("🧹 [Cleanup] Removed plugin context from LiveBeansView");
                    }
                }
            }
        } catch (ClassNotFoundException ignored) {
            // Spring 6.x 已移除 LiveBeansView，跳过
        } catch (Exception e) {
            log.warn("Failed to clear LiveBeansView: {}", e.getMessage());
        }
    }

    // ==================== 宿主 BeanFactory 缓存清理 ====================

    /**
     * 从宿主 BeanFactory 的 mergedBeanDefinitions 缓存中移除插件相关条目
     * Spring 的 removeBeanDefinition() 只标记 stale，不实际删除
     */
    private static void clearMergedBeanDefinitions(ConfigurableApplicationContext hostContext,
            ClassLoader pluginClassLoader) {
        try {
            Object beanFactory = hostContext.getAutowireCapableBeanFactory();

            Field mergedField = ReflectionUtils.findField(
                    org.springframework.beans.factory.support.AbstractBeanFactory.class,
                    "mergedBeanDefinitions");
            if (mergedField != null) {
                ReflectionUtils.makeAccessible(mergedField);
                @SuppressWarnings("unchecked")
                Map<String, ?> merged = (Map<String, ?>) ReflectionUtils.getField(mergedField, beanFactory);
                if (merged != null) {
                    int before = merged.size();
                    merged.entrySet().removeIf(entry -> {
                        Object bd = entry.getValue();
                        return isBeanDefinitionRelated(bd, pluginClassLoader);
                    });
                    int removed = before - merged.size();
                    if (removed > 0) {
                        log.info("🧹 [Cleanup] mergedBeanDefinitions: removed {} entries", removed);
                    }
                }
            }
        } catch (Exception e) {
            // 兜底：调用 clearMetadataCache
            try {
                hostContext.getAutowireCapableBeanFactory();
                org.springframework.beans.factory.support.DefaultListableBeanFactory bf = (org.springframework.beans.factory.support.DefaultListableBeanFactory) hostContext
                        .getAutowireCapableBeanFactory();
                bf.clearMetadataCache();
                log.info("🧹 [Cleanup] Called clearMetadataCache as fallback");
            } catch (Exception ex) {
                log.warn("Failed to clear mergedBeanDefinitions: {}", e.getMessage());
            }
        }
    }

    /**
     * 检查 BeanDefinition 的 beanClass 是否由插件 ClassLoader 加载
     */
    private static boolean isBeanDefinitionRelated(Object bd, ClassLoader pluginClassLoader) {
        try {
            // RootBeanDefinition.hasBeanClass() + getBeanClass()
            Method hasBeanClass = bd.getClass().getMethod("hasBeanClass");
            if ((Boolean) hasBeanClass.invoke(bd)) {
                Method getBeanClass = bd.getClass().getMethod("getBeanClass");
                Class<?> beanClass = (Class<?>) getBeanClass.invoke(bd);
                return beanClass != null && beanClass.getClassLoader() == pluginClassLoader;
            }

            // 检查 resolvedTargetType
            Field targetTypeField = ReflectionUtils.findField(bd.getClass(), "resolvedTargetType");
            if (targetTypeField != null) {
                ReflectionUtils.makeAccessible(targetTypeField);
                Object targetType = ReflectionUtils.getField(targetTypeField, bd);
                if (targetType instanceof Class<?>) {
                    return ((Class<?>) targetType).getClassLoader() == pluginClassLoader;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * 清理宿主 singletonObjects 中引用了插件 Context 的事件对象
     * （ApplicationAvailabilityBean 会缓存 AvailabilityChangeEvent）
     */
    private static void clearAvailabilityEvents(ConfigurableApplicationContext hostContext,
            ClassLoader pluginClassLoader) {
        try {
            String[] beanNames = hostContext.getBeanNamesForType(
                    org.springframework.boot.availability.ApplicationAvailabilityBean.class);

            for (String beanName : beanNames) {
                Object bean = hostContext.getBean(beanName);
                Field eventsField = ReflectionUtils.findField(bean.getClass(), "events");
                if (eventsField != null) {
                    ReflectionUtils.makeAccessible(eventsField);
                    @SuppressWarnings("unchecked")
                    Map<?, ?> events = (Map<?, ?>) ReflectionUtils.getField(eventsField, bean);
                    if (events != null) {
                        int before = events.size();
                        events.values().removeIf(event -> isRelatedToClassLoader(event, pluginClassLoader));
                        int removed = before - events.size();
                        if (removed > 0) {
                            log.info("🧹 [Cleanup] ApplicationAvailabilityBean.events: removed {} entries", removed);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to clear AvailabilityEvents: {}", e.getMessage());
        }
    }

    /**
     * 清理宿主 SingletonBeanRegistry 中插件相关的单例
     */
    private static void clearSingletonBeanRegistry(ConfigurableApplicationContext hostContext,
            ClassLoader pluginClassLoader) {
        try {
            org.springframework.beans.factory.support.DefaultListableBeanFactory bf = (org.springframework.beans.factory.support.DefaultListableBeanFactory) hostContext
                    .getAutowireCapableBeanFactory();

            // singletonObjects
            Field singletonField = ReflectionUtils.findField(
                    org.springframework.beans.factory.support.DefaultSingletonBeanRegistry.class,
                    "singletonObjects");
            if (singletonField != null) {
                ReflectionUtils.makeAccessible(singletonField);
                @SuppressWarnings("unchecked")
                Map<String, Object> singletons = (Map<String, Object>) ReflectionUtils.getField(singletonField, bf);
                if (singletons != null) {
                    List<String> toRemove = new ArrayList<>();
                    singletons.forEach((name, bean) -> {
                        if (bean != null && bean.getClass().getClassLoader() == pluginClassLoader) {
                            toRemove.add(name);
                        }
                    });
                    for (String name : toRemove) {
                        try {
                            bf.destroySingleton(name);
                            log.debug("🧹 [Cleanup] Destroyed singleton: {}", name);
                        } catch (Exception e) {
                            singletons.remove(name);
                            log.debug("🧹 [Cleanup] Removed singleton directly: {}", name);
                        }
                    }
                    if (!toRemove.isEmpty()) {
                        log.info("🧹 [Cleanup] Removed {} plugin singletons from host", toRemove.size());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to clear singleton registry: {}", e.getMessage());
        }
    }

    // ==================== HandlerAdapter 缓存清理 ====================

    /**
     * 清理 RequestMappingHandlerAdapter 的内部缓存
     */
    private static void clearAdapterCaches(RequestMappingHandlerAdapter adapter,
            ClassLoader pluginClassLoader) {
        try {
            // 普通缓存 (ConcurrentHashMap<Class<?>, ?>)
            String[] cacheNames = {
                    "sessionAttributesHandlerCache",
                    "initBinderCache",
                    "modelAttributeCache"
            };

            for (String cacheName : cacheNames) {
                clearClassKeyedCache(adapter, cacheName, pluginClassLoader);
            }

            // Advice 缓存 (LinkedHashMap<ControllerAdviceBean, Set<Method>>)
            String[] adviceCacheNames = {
                    "initBinderAdviceCache",
                    "modelAttributeAdviceCache"
            };

            for (String cacheName : adviceCacheNames) {
                clearAdviceCache(adapter, cacheName, pluginClassLoader);
            }

            log.debug("🧹 [Cleanup] Cleared HandlerAdapter caches");
        } catch (Exception e) {
            log.warn("Failed to clear HandlerAdapter caches: {}", e.getMessage());
        }
    }

    private static void clearClassKeyedCache(Object target, String fieldName,
            ClassLoader pluginClassLoader) {
        try {
            Field field = ReflectionUtils.findField(target.getClass(), fieldName);
            if (field == null)
                return;
            ReflectionUtils.makeAccessible(field);
            @SuppressWarnings("unchecked")
            Map<Class<?>, ?> cache = (Map<Class<?>, ?>) ReflectionUtils.getField(field, target);
            if (cache != null) {
                cache.keySet().removeIf(clazz -> clazz != null && clazz.getClassLoader() == pluginClassLoader);
            }
        } catch (Exception e) {
            log.trace("Failed to clear cache {}: {}", fieldName, e.getMessage());
        }
    }

    private static void clearAdviceCache(Object target, String fieldName,
            ClassLoader pluginClassLoader) {
        try {
            Field field = ReflectionUtils.findField(target.getClass(), fieldName);
            if (field == null)
                return;
            ReflectionUtils.makeAccessible(field);
            @SuppressWarnings("unchecked")
            Map<ControllerAdviceBean, ?> cache = (Map<ControllerAdviceBean, ?>) ReflectionUtils.getField(field, target);
            if (cache != null) {
                cache.keySet().removeIf(advice -> {
                    Class<?> type = advice.getBeanType();
                    return type != null && type.getClassLoader() == pluginClassLoader;
                });
            }
        } catch (Exception e) {
            log.trace("Failed to clear advice cache {}: {}", fieldName, e.getMessage());
        }
    }

    // ==================== Java 内省缓存清理 ====================

    /**
     * 清理 Java 和 Spring 的内省缓存
     */
    private static void clearIntrospectionCaches(ClassLoader pluginClassLoader) {
        try {
            CachedIntrospectionResults.clearClassLoader(pluginClassLoader);
        } catch (Exception e) {
            log.debug("Failed to clear CachedIntrospectionResults: {}", e.getMessage());
        }

        try {
            Introspector.flushCaches();
        } catch (Exception e) {
            log.debug("Failed to flush Introspector caches: {}", e.getMessage());
        }

        // ResourceBundle 缓存
        try {
            ResourceBundle.clearCache(pluginClassLoader);
        } catch (Exception e) {
            log.debug("Failed to clear ResourceBundle cache: {}", e.getMessage());
        }
    }

    // ==================== ClassLoader 关闭 ====================

    /**
     * 安全关闭 ClassLoader
     */
    private static void closeClassLoader(ClassLoader classLoader) {
        if (classLoader instanceof AutoCloseable) {
            try {
                ((AutoCloseable) classLoader).close();
                log.info("🧹 [Cleanup] ClassLoader closed: {}", classLoader);
            } catch (Exception e) {
                log.warn("Failed to close ClassLoader: {}", e.getMessage());
            }
        }
    }
}