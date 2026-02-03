package com.lingframe.starter.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.beans.factory.support.GenericBeanDefinition;

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
 * 1. 将插件 Controller 方法直接注册到宿主 Spring MVC
 * 2. 维护 HandlerMethod -> Metadata 映射，供 Interceptor 查询
 * 3. 插件卸载时彻底清理路由，防止内存泄漏
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
     * 注册插件 Controller 方法到 Spring MVC
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
        }

        try {
            // 1. 将插件 Bean 注册到宿主 Context (供 SpringDoc 发现)
            // 使用 BeanDefinition + InstanceSupplier 确保 SpringDoc 能读取到注解元数据
            // 关键：必须使用原始类 (Target Class) 而不是代理类，否则注解可能丢失
            Class<?> userClass = AopUtils.getTargetClass(metadata.getTargetBean());
            String proxyBeanName = metadata.getPluginId() + ":" + userClass.getName();

            if (hostContext instanceof GenericApplicationContext gac && !gac.containsBeanDefinition(proxyBeanName)) {
                GenericBeanDefinition bd = new GenericBeanDefinition();
                bd.setBeanClass(userClass);
                bd.setInstanceSupplier(metadata::getTargetBean);
                bd.setScope("singleton");
                // 标记为 Primary 或其他特征可能有助于发现，但暂不加
                gac.registerBeanDefinition(proxyBeanName, bd);
                log.info("🔥 [LingFrame Web] Registered Plugin Bean for SpringDoc: {} (Class: {})", proxyBeanName,
                        userClass.getName());
            } else {
                log.debug("Plugin Bean already registered: {}", proxyBeanName);
            }

            // 2. 构建 RequestMappingInfo
            RequestMappingInfo info = RequestMappingInfo
                    .paths(metadata.getUrlPattern())
                    .methods(RequestMethod.valueOf(metadata.getHttpMethod()))
                    .build();

            // 3. 直接注册插件 Controller Bean 和 Method 到 Spring MVC
            // 关键修复：使用 Bean Name (String) 注册，而不是实例。
            // 这样 SpringDoc 在扫描时会通过 Bean Name 找到我们在上面注册的 GenericBeanDefinition，
            // 进而读取到 setBeanClass(userClass) 设置的原始类，从而正确解析注解。
            hostMapping.registerMapping(info, proxyBeanName, metadata.getTargetMethod());

            // 存储映射关系
            metadataMap.put(routeKey, metadata);
            mappingInfoMap.put(routeKey, info);

            log.info("🌍 [LingFrame Web] Registered: {} {} -> {}.{}",
                    metadata.getHttpMethod(), metadata.getUrlPattern(),
                    metadata.getPluginId(), metadata.getTargetMethod().getName());
        } catch (Exception e) {
            log.error("Failed to register web mapping: {} {}", metadata.getHttpMethod(), metadata.getUrlPattern(), e);
        }
    }

    /**
     * 注销插件的所有接口
     */
    public void unregister(String pluginId) {
        if (hostMapping == null)
            return;

        log.info("♻️ [LingFrame Web] Unregistering interfaces for plugin: {}", pluginId);

        List<String> keysToRemove = new ArrayList<>();
        AtomicReference<ClassLoader> pluginLoader = new AtomicReference<>();  // 记录插件 ClassLoader 用于清理

        metadataMap.forEach((key, meta) -> {
            if (meta.getPluginId().equals(pluginId)) {
                keysToRemove.add(key);
                pluginLoader.set(meta.getClassLoader());  // 取一个就行（所有接口同 Loader）

                // 1. 从 Spring MVC 注销
                RequestMappingInfo info = mappingInfoMap.get(key);
                if (info != null) {
                    try {
                        hostMapping.unregisterMapping(info);
                        log.debug("Unregistered mapping: {}", key);
                    } catch (Exception e) {
                        log.warn("Failed to unregister mapping: {}", key, e);
                    }
                }

                // 2. 从宿主 Context 移除 Bean (防止内存泄漏)
                if (hostContext instanceof GenericApplicationContext gac) {
                    String proxyBeanName = meta.getPluginId() + ":" + meta.getTargetBean().getClass().getName();
                    if (gac.containsBeanDefinition(proxyBeanName)) {
                        gac.removeBeanDefinition(proxyBeanName);
                    }
                }
            }
        });

        // 清理本地缓存
        for (String key : keysToRemove) {
            metadataMap.remove(key);
            mappingInfoMap.remove(key);
        }

        // 深度清理 HandlerAdapter 缓存，防止 Metaspace 泄漏
        if (hostAdapter != null && pluginLoader.get() != null) {
            clearAdapterCaches(pluginLoader.get());
        }

        log.info("♻️ [LingFrame Web] Unregistered {} interfaces for plugin: {}", keysToRemove.size(), pluginId);
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
     * 反射清理 Adapter 的插件相关缓存
     */
    private void clearAdapterCaches(ClassLoader pluginLoader) {
        try {
            // 清理普通缓存 (ConcurrentHashMap<Class<?>, ?>)
            clearCache("sessionAttributesHandlerCache", pluginLoader);
            clearCache("initBinderCache", pluginLoader);
            clearCache("modelAttributeCache", pluginLoader);

            // 清理 Advice 缓存 (LinkedHashMap<ControllerAdviceBean, Set<Method>>)
            clearAdviceCache("initBinderAdviceCache", pluginLoader);
            clearAdviceCache("modelAttributeAdviceCache", pluginLoader);

            log.debug("Cleared HandlerAdapter caches for plugin ClassLoader: {}", pluginLoader);
        } catch (Exception e) {
            log.warn("Failed to clear HandlerAdapter caches", e);
        }
    }

    private void clearCache(String fieldName, ClassLoader pluginLoader) throws Exception {
        Field field = ReflectionUtils.findField(hostAdapter.getClass(), fieldName);
        if (field == null) return;
        ReflectionUtils.makeAccessible(field);
        @SuppressWarnings("unchecked")
        Map<Class<?>, ?> cache = (Map<Class<?>, ?>) ReflectionUtils.getField(field, hostAdapter);
        if (cache != null) {
            cache.keySet().removeIf(clazz -> clazz != null && clazz.getClassLoader() == pluginLoader);
        }
    }

    private void clearAdviceCache(String fieldName, ClassLoader pluginLoader) throws Exception {
        Field field = ReflectionUtils.findField(hostAdapter.getClass(), fieldName);
        if (field == null) return;
        ReflectionUtils.makeAccessible(field);
        @SuppressWarnings("unchecked")
        Map<ControllerAdviceBean, Set<Method>> cache = (Map<ControllerAdviceBean, Set<Method>>) ReflectionUtils.getField(field, hostAdapter);
        if (cache != null) {
            cache.keySet().removeIf(advice -> {
                Class<?> type = advice.getBeanType();
                return type != null && type.getClassLoader() == pluginLoader;
            });
        }
    }
}