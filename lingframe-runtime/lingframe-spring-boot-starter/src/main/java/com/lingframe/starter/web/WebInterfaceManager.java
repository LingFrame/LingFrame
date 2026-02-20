package com.lingframe.starter.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Web 接口动态管理器（原生注册版）
 * 职责：
 * 1. 将单元 Controller 方法直接注册到灵核 Spring MVC
 * 2. 维护 HandlerMethod -> Metadata 映射，供 Interceptor 查询
 * 3. 单元卸载时彻底清理路由，防止内存泄漏
 */
@Slf4j
public class WebInterfaceManager {

    // HandlerMethod 标识 -> 元数据映射
    private final Map<String, WebInterfaceMetadata> metadataMap = new ConcurrentHashMap<>();

    // 路由键 -> RequestMappingInfo 映射（用于卸载）
    private final Map<String, RequestMappingInfo> mappingInfoMap = new ConcurrentHashMap<>();

    private RequestMappingHandlerMapping hostMapping;
    private RequestMappingHandlerAdapter hostAdapter;
    private ConfigurableApplicationContext hostContext;

    /**
     * 初始化方法，由 AutoConfiguration 调用
     */
    public void init(RequestMappingHandlerMapping mapping,
            RequestMappingHandlerAdapter adapter,
            ConfigurableApplicationContext hostContext) {
        this.hostMapping = mapping;
        this.hostAdapter = adapter;
        this.hostContext = hostContext;
        log.info("🌍 [LingFrame Web] WebInterfaceManager initialized with native registration");
    }

    /**
     * 注册单元 Controller 方法到 Spring MVC
     */
    public void register(WebInterfaceMetadata metadata) {
        if (hostMapping == null || hostContext == null) {
            log.warn("WebInterfaceManager not initialized, skipping registration: {}", metadata.getUrlPattern());
            return;
        }

        String routeKey = buildRouteKey(metadata);

        // 检查路由冲突
        if (metadataMap.containsKey(routeKey)) {
            log.warn("⚠️ [LingFrame Web] Route conflict detected, overwriting: {} [{}]",
                    metadata.getHttpMethod(), metadata.getUrlPattern());

            // 🔥 修复：如果存在冲突，先移除旧映射（热替换机制）
            RequestMappingInfo oldInfo = mappingInfoMap.get(routeKey);
            if (oldInfo != null) {
                try {
                    hostMapping.unregisterMapping(oldInfo);
                    log.info("♻️ [LingFrame Web] Unregistered conflicting mapping: {}", routeKey);
                } catch (Exception e) {
                    log.warn("Failed to unregister conflicting mapping: {}", routeKey, e);
                }
            }
        }

        try {
            // 1. 将单元 Bean 注册到灵核 Context (供 SpringDoc 发现)
            // 使用 BeanDefinition + InstanceSupplier 确保 SpringDoc 能读取到注解元数据
            // 关键：必须使用原始类 (Target Class) 而不是代理类，否则注解可能丢失
            Class<?> userClass = AopUtils.getTargetClass(metadata.getTargetBean());
            String proxyBeanName = metadata.getLingId() + ":" + userClass.getName();

            if (hostContext instanceof GenericApplicationContext && !((GenericApplicationContext) hostContext).containsBeanDefinition(proxyBeanName)) {
                GenericApplicationContext gac = (GenericApplicationContext) hostContext;
                GenericBeanDefinition bd = new GenericBeanDefinition();
                bd.setBeanClass(userClass);
                bd.setInstanceSupplier(metadata::getTargetBean);
                bd.setScope("singleton");
                // 标记为 Primary 或其他特征可能有助于发现，但暂不加
                gac.registerBeanDefinition(proxyBeanName, bd);
                log.info("🔥 [LingFrame Web] Registered ling Bean for SpringDoc: {} (Class: {})", proxyBeanName,
                        userClass.getName());
            } else {
                log.debug("Ling Bean already registered: {}", proxyBeanName);
            }

            // 2. 构建 RequestMappingInfo
            RequestMappingInfo info = RequestMappingInfo
                    .paths(metadata.getUrlPattern())
                    .methods(RequestMethod.valueOf(metadata.getHttpMethod()))
                    .build();

            // 3. 直接注册单元 Controller Bean 和 Method 到 Spring MVC
            // 关键修复：使用 Bean Name (String) 注册，而不是实例。
            // 这样 SpringDoc 在扫描时会通过 Bean Name 找到我们在上面注册的 GenericBeanDefinition，
            // 进而读取到 setBeanClass(userClass) 设置的原始类，从而正确解析注解。
            hostMapping.registerMapping(info, proxyBeanName, metadata.getTargetMethod());

            // 存储映射关系
            metadataMap.put(routeKey, metadata);
            mappingInfoMap.put(routeKey, info);

            log.info("🌍 [LingFrame Web] Registered: {} {} -> {}.{}",
                    metadata.getHttpMethod(), metadata.getUrlPattern(),
                    metadata.getLingId(), metadata.getTargetMethod().getName());
        } catch (Exception e) {
            log.error("Failed to register web mapping: {} {}", metadata.getHttpMethod(), metadata.getUrlPattern(), e);
        }
    }

    /**
     * 注销单元的所有接口
     */
    public void unregister(String lingId) {
        if (hostMapping == null) return;

        log.info("♻️ [LingFrame Web] Unregistering interfaces for ling: {}", lingId);

        List<String> keysToRemove = new ArrayList<>();
        AtomicReference<ClassLoader> lingLoader = new AtomicReference<>();
        List<String> beanNamesToRemove = new ArrayList<>();  // 收集要移除的 bean 名

        metadataMap.forEach((key, meta) -> {
            if (meta.getLingId().equals(lingId)) {
                keysToRemove.add(key);
                lingLoader.set(meta.getClassLoader());

                // 1. 从 Spring MVC 注销
                RequestMappingInfo info = mappingInfoMap.get(key);
                if (info != null) {
                    try {
                        hostMapping.unregisterMapping(info);
                    } catch (Exception e) {
                        log.warn("Failed to unregister mapping: {}", key, e);
                    }
                }

                // 2. 🔥 修复：使用与 register 相同的逻辑计算 bean 名
                if (hostContext instanceof GenericApplicationContext) {
                    Class<?> userClass = AopUtils.getTargetClass(meta.getTargetBean());
                    String proxyBeanName = meta.getLingId() + ":" + userClass.getName();
                    beanNamesToRemove.add(proxyBeanName);
                }
            }
        });

        // 3. 🔥 修复：从灵核 Context 移除 Bean 定义
        if (hostContext instanceof GenericApplicationContext) {
            GenericApplicationContext gac = (GenericApplicationContext) hostContext;
            for (String beanName : beanNamesToRemove) {
                if (gac.containsBeanDefinition(beanName)) {
                    try {
                        DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) gac.getBeanFactory();

                        // 1. 从单例缓存中移除（singletonObjects, earlySingletonObjects 等）
                        if (beanFactory.containsSingleton(beanName)) {
                            beanFactory.destroySingleton(beanName);
                        }

                        // 2. 移除 BeanDefinition（从 beanDefinitionMap 中删除）
                        if (beanFactory.containsBeanDefinition(beanName)) {
                            beanFactory.removeBeanDefinition(beanName);
                        }

                        beanFactory.clearMetadataCache();
                        log.debug("Cleaned up bean: {}", beanName);
                    } catch (Exception e) {
                        log.warn("Failed to cleanup bean: {}", beanName, e);
                    }
                }
            }

            // 4. 🔥🔥🔥 关键修复：强制清理 mergedBeanDefinitions 缓存
            clearMergedBeanDefinitions(gac, beanNamesToRemove);
        }

        // 清理本地缓存
        for (String key : keysToRemove) {
            WebInterfaceMetadata meta = metadataMap.remove(key);
            if (meta != null) {
                meta.clearReferences(); // ← 主动断开引用
            }
            mappingInfoMap.remove(key);
        }

        // 深度清理 HandlerAdapter 缓存
        if (hostAdapter != null && lingLoader.get() != null) {
            clearAdapterCaches(lingLoader.get());
        }

        log.info("♻️ [LingFrame Web] Unregistered {} interfaces for ling: {}",
                keysToRemove.size(), lingId);
    }

    /**
     * 🔥 强制从 mergedBeanDefinitions 中移除指定条目
     * Spring 的 removeBeanDefinition 只标记 stale，不实际删除
     */
    private void clearMergedBeanDefinitions(GenericApplicationContext gac,
            List<String> beanNames) {
        try {
            Field mergedField = ReflectionUtils.findField(
                    org.springframework.beans.factory.support.AbstractBeanFactory.class,
                    "mergedBeanDefinitions");
            if (mergedField != null) {
                ReflectionUtils.makeAccessible(mergedField);
                @SuppressWarnings("unchecked")
                Map<String, ?> mergedBeanDefinitions = (Map<String, ?>) ReflectionUtils.getField(mergedField,
                        gac.getBeanFactory());
                if (mergedBeanDefinitions != null) {
                    for (String beanName : beanNames) {
                        mergedBeanDefinitions.remove(beanName);
                        log.debug("Removed mergedBeanDefinition: {}", beanName);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to clear mergedBeanDefinitions, falling back to clearMetadataCache", e);
            // 兜底：清除所有缓存（影响范围大但安全）
            gac.getBeanFactory().clearMetadataCache();
        }
    }

    /**
     * 根据 HandlerMethod 获取元数据
     * 供 LingWebGovernanceInterceptor 调用
     */
    public WebInterfaceMetadata getMetadata(HandlerMethod handlerMethod) {
        // 通过 Bean 和 Method 构建查找键
        Object bean = handlerMethod.getBean();
        Method method = handlerMethod.getMethod();

        // 遍历查找匹配的元数据
        for (WebInterfaceMetadata meta : metadataMap.values()) {
            if (isSameHandler(meta, bean, method)) {
                return meta;
            }
        }
        return null;
    }

    /**
     * 判断是否是同一个处理器
     */
    private boolean isSameHandler(WebInterfaceMetadata meta, Object bean, Method method) {
        // 比较 Bean 实例和方法签名
        if (meta.getTargetBean() == bean) {
            return meta.getTargetMethod().equals(method);
        }
        // 处理代理情况：比较方法名和参数类型
        if (meta.getTargetMethod().getName().equals(method.getName())) {
            Class<?>[] metaParams = meta.getTargetMethod().getParameterTypes();
            Class<?>[] methodParams = method.getParameterTypes();
            if (metaParams.length == methodParams.length) {
                for (int i = 0; i < metaParams.length; i++) {
                    if (!metaParams[i].equals(methodParams[i])) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 构建路由键：httpMethod#urlPattern
     */
    private String buildRouteKey(WebInterfaceMetadata metadata) {
        return metadata.getHttpMethod() + "#" + metadata.getUrlPattern();
    }

    /**
     * 反射清理 Adapter 的单元相关缓存
     */
    private void clearAdapterCaches(ClassLoader lingLoader) {
        try {
            // 清理普通缓存 (ConcurrentHashMap<Class<?>, ?>)
            clearCache("sessionAttributesHandlerCache", lingLoader);
            clearCache("initBinderCache", lingLoader);
            clearCache("modelAttributeCache", lingLoader);

            // 清理 Advice 缓存 (LinkedHashMap<ControllerAdviceBean, Set<Method>>)
            clearAdviceCache("initBinderAdviceCache", lingLoader);
            clearAdviceCache("modelAttributeAdviceCache", lingLoader);

            log.debug("Cleared HandlerAdapter caches for ling ClassLoader: {}", lingLoader);
        } catch (Exception e) {
            log.warn("Failed to clear HandlerAdapter caches", e);
        }
    }

    private void clearCache(String fieldName, ClassLoader lingLoader) throws Exception {
        Field field = ReflectionUtils.findField(hostAdapter.getClass(), fieldName);
        if (field == null)
            return;
        ReflectionUtils.makeAccessible(field);
        @SuppressWarnings("unchecked")
        Map<Class<?>, ?> cache = (Map<Class<?>, ?>) ReflectionUtils.getField(field, hostAdapter);
        if (cache != null) {
            cache.keySet().removeIf(clazz -> clazz != null && clazz.getClassLoader() == lingLoader);
        }
    }

    private void clearAdviceCache(String fieldName, ClassLoader lingLoader) throws Exception {
        Field field = ReflectionUtils.findField(hostAdapter.getClass(), fieldName);
        if (field == null)
            return;
        ReflectionUtils.makeAccessible(field);
        @SuppressWarnings("unchecked")
        Map<ControllerAdviceBean, Set<Method>> cache = (Map<ControllerAdviceBean, Set<Method>>) ReflectionUtils
                .getField(field, hostAdapter);
        if (cache != null) {
            cache.keySet().removeIf(advice -> {
                Class<?> type = advice.getBeanType();
                return type != null && type.getClassLoader() == lingLoader;
            });
        }
    }
}