package com.lingframe.starter.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.support.AbstractBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.lingframe.api.exception.LingException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.spi.TrafficRouter;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.PreDestroy;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolverComposite;
import org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Web 接口动态管理器（原生注册版）
 * 职责：
 * 1. 将灵元 Controller 方法直接注册到灵核 Spring MVC
 * 2. 维护 HandlerMethod -> Metadata 映射，供 Interceptor 查询
 * 3. 灵元卸载时彻底清理路由，防止内存泄漏
 */
@Slf4j
public class WebInterfaceManager {

    // 路由键 -> 多版本元数据
    private final Map<String, List<WebInterfaceMetadata>> metadataMap = new ConcurrentHashMap<>();

    // 路由键 -> RequestMappingInfo 映射（用于卸载）
    private final Map<String, RequestMappingInfo> mappingInfoMap = new ConcurrentHashMap<>();

    // 路由键 -> 入口处理器
    private final Map<String, LingWebEntryHandler> routeHandlerMap = new ConcurrentHashMap<>();

    private final ExecutorService registryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "LingFrame-WebInterfaceManager");
        thread.setDaemon(true);
        return thread;
    });

    private RequestMappingHandlerMapping hostMapping;
    private RequestMappingHandlerAdapter hostAdapter;
    private ConfigurableApplicationContext hostContext;

    private final LingRepository lingRepository;
    private final TrafficRouter trafficRouter;

    private volatile HandlerMethodArgumentResolverComposite argumentResolvers;
    private volatile ParameterNameDiscoverer parameterNameDiscoverer;

    public static final String REQUEST_METADATA_KEY = "ling.web.metadata";
    public static final String REQUEST_TARGET_VERSION_KEY = "ling.target.version";

    public WebInterfaceManager(LingRepository lingRepository, TrafficRouter trafficRouter) {
        this.lingRepository = lingRepository;
        this.trafficRouter = trafficRouter;
    }

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
     * 注册灵元 Controller 方法到 Spring MVC
     */
    public void register(WebInterfaceMetadata metadata) {
        registryExecutor.execute(() -> registerInternal(metadata, false));
    }

    public void registerSync(WebInterfaceMetadata metadata) {
        try {
            registryExecutor.submit(() -> registerInternal(metadata, true)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LingException("Interrupted while registering web mapping", e);
        } catch (ExecutionException e) {
            throw new LingException("Failed to register web mapping", e.getCause());
        }
    }

    private void registerInternal(WebInterfaceMetadata metadata, boolean throwOnError) {
        if (hostMapping == null || hostContext == null) {
            log.warn("WebInterfaceManager not initialized, skipping registration: {}", metadata.getUrlPattern());
            return;
        }

        String routeKey = buildRouteKey(metadata);

        try {
            // 1. 将灵元 Bean 注册到灵核 Context (供 SpringDoc 发现)
            // 使用 BeanDefinition + InstanceSupplier 确保 SpringDoc 能读取到注解元数据
            // 关键：必须使用原始类 (Target Class) 而不是代理类，否则注解可能丢失
            Class<?> userClass = AopUtils.getTargetClass(metadata.getTargetBean());
            String version = metadata.getVersion();
            String proxyBeanName = metadata.getLingId() + ":" + (version != null ? version : "unknown")
                    + ":" + userClass.getName();

            if (hostContext instanceof GenericApplicationContext
                    && !hostContext.containsBeanDefinition(proxyBeanName)) {
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

            // 3. 路由入口注册：同一路由只保留一个入口，版本选择下沉到路由层
            if (!mappingInfoMap.containsKey(routeKey)) {
                LingWebEntryHandler entryHandler = new LingWebEntryHandler(this, routeKey);
                Method dispatchMethod = ReflectionUtils.findMethod(LingWebEntryHandler.class, "dispatch",
                        HttpServletRequest.class, HttpServletResponse.class);
                if (dispatchMethod == null) {
                    throw new IllegalStateException("dispatch method not found for route: " + routeKey);
                }
                hostMapping.registerMapping(info, entryHandler, dispatchMethod);
                mappingInfoMap.put(routeKey, info);
                routeHandlerMap.put(routeKey, entryHandler);
                log.info("🌍 [LingFrame Web] Registered route entry: {} {}", metadata.getHttpMethod(),
                        metadata.getUrlPattern());
            }

            // 4. 存储多版本元数据
            metadataMap.compute(routeKey, (key, list) -> {
                List<WebInterfaceMetadata> target = (list != null) ? list : new ArrayList<>();
                target.removeIf(existing -> {
                    String v1 = existing.getVersion();
                    String v2 = metadata.getVersion();
                    if (v1 == null || v2 == null) {
                        return false;
                    }
                    return v1.equals(v2) && existing.getTargetMethod().equals(metadata.getTargetMethod());
                });
                target.add(metadata);
                return target;
            });

            log.info("🌍 [LingFrame Web] Registered: {} {} -> {}.{}",
                    metadata.getHttpMethod(), metadata.getUrlPattern(),
                    metadata.getLingId(), metadata.getTargetMethod().getName());
        } catch (Exception e) {
            if (throwOnError) {
                throw new LingException("Failed to register web mapping: " + metadata.getHttpMethod() + " "
                        + metadata.getUrlPattern(), e);
            }
            log.error("Failed to register web mapping: {} {}", metadata.getHttpMethod(), metadata.getUrlPattern(), e);
        }
    }

    /**
     * 注销灵元的所有接口或特定版本的接口
     */
    public void unregister(String lingId, ClassLoader targetLoader) {
        registryExecutor.execute(() -> unregisterInternal(lingId, targetLoader));
    }

    private void unregisterInternal(String lingId, ClassLoader targetLoader) {
        if (hostMapping == null) {
            return;
        }

        log.info("♻️ [LingFrame Web] Unregistering interfaces for ling: {} (ClassLoader: {})", lingId,
                targetLoader != null ? targetLoader.hashCode() : "ALL");

        List<String> routesToRemove = new ArrayList<>();
        AtomicReference<ClassLoader> lingLoader = new AtomicReference<>();
        List<String> beanNamesToRemove = new ArrayList<>(); // 收集要移除的 bean 名
        Map<String, List<WebInterfaceMetadata>> remainingMap = new ConcurrentHashMap<>();
        List<WebInterfaceMetadata> removedMetas = new ArrayList<>();

        metadataMap.forEach((routeKey, metas) -> {
            if (metas == null || metas.isEmpty()) {
                return;
            }
            List<WebInterfaceMetadata> remaining = new ArrayList<>();
            for (WebInterfaceMetadata meta : metas) {
                if (meta.getLingId().equals(lingId)
                        && (targetLoader == null || meta.getClassLoader() == targetLoader)) {
                    removedMetas.add(meta);
                    lingLoader.set(meta.getClassLoader());

                    if (hostContext instanceof GenericApplicationContext) {
                        Class<?> userClass = AopUtils.getTargetClass(meta.getTargetBean());
                        String version = meta.getVersion();
                        String proxyBeanName = meta.getLingId() + ":" + (version != null ? version : "unknown")
                                + ":" + userClass.getName();
                        beanNamesToRemove.add(proxyBeanName);
                    }
                } else {
                    remaining.add(meta);
                }
            }

            if (remaining.isEmpty()) {
                routesToRemove.add(routeKey);
            } else {
                remainingMap.put(routeKey, remaining);
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

        // 更新剩余元数据
        for (Map.Entry<String, List<WebInterfaceMetadata>> entry : remainingMap.entrySet()) {
            metadataMap.put(entry.getKey(), entry.getValue());
        }
        for (String routeKey : routesToRemove) {
            metadataMap.remove(routeKey);
        }

        // 清理被移除的元数据引用
        for (WebInterfaceMetadata meta : removedMetas) {
            meta.clearReferences(); // ← 主动断开引用
        }

        // 清理路由入口映射
        for (String routeKey : routesToRemove) {
            RequestMappingInfo info = mappingInfoMap.remove(routeKey);
            if (info != null) {
                try {
                    hostMapping.unregisterMapping(info);
                } catch (Exception e) {
                    log.warn("Failed to unregister mapping: {}", routeKey, e);
                }
            }
            routeHandlerMap.remove(routeKey);
        }

        // 深度清理 HandlerAdapter 缓存
        if (hostAdapter != null && (targetLoader != null || lingLoader.get() != null)) {
            clearAdapterCaches(targetLoader != null ? targetLoader : lingLoader.get());
        }

        log.info("♻️ [LingFrame Web] Unregistered {} interfaces for ling: {}",
                removedMetas.size(), lingId);
    }

    @PreDestroy
    public void shutdown() {
        registryExecutor.shutdown();
        try {
            if (!registryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                registryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            registryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 🔥 强制从 mergedBeanDefinitions 中移除指定条目
     * Spring 的 removeBeanDefinition 只标记 stale，不实际删除
     */
    private void clearMergedBeanDefinitions(GenericApplicationContext gac,
            List<String> beanNames) {
        try {
            Field mergedField = ReflectionUtils.findField(
                    AbstractBeanFactory.class,
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
     * 供 LingWebGovernanceFilter 调用
     */
    public WebInterfaceMetadata getMetadata(HttpServletRequest request, HandlerMethod handlerMethod) {
        if (request != null) {
            Object cached = request.getAttribute(REQUEST_METADATA_KEY);
            if (cached instanceof WebInterfaceMetadata) {
                return (WebInterfaceMetadata) cached;
            }
        }

        Object bean = handlerMethod.getBean();
        if (bean instanceof LingWebEntryHandler) {
            String routeKey = ((LingWebEntryHandler) bean).getRouteKey();
            WebInterfaceMetadata meta = resolveByRouteKey(routeKey, request);
            if (meta != null && request != null) {
                Object forced = request.getAttribute(REQUEST_TARGET_VERSION_KEY);
                if (forced != null) {
                    request.setAttribute(REQUEST_METADATA_KEY, meta);
                }
            }
            return meta;
        }

        // 兼容：仍允许直接绑定到原 Controller 的情况
        Method method = handlerMethod.getMethod();
        for (List<WebInterfaceMetadata> metas : metadataMap.values()) {
            for (WebInterfaceMetadata meta : metas) {
                if (isSameHandler(meta, bean, method)) {
                    if (request != null) {
                        request.setAttribute(REQUEST_METADATA_KEY, meta);
                    }
                    return meta;
                }
            }
        }
        return null;
    }

    public WebInterfaceMetadata getMetadata(HandlerMethod handlerMethod) {
        return getMetadata(null, handlerMethod);
    }

    /**
     * 判断是否是同一个处理器
     */
    private boolean isSameHandler(WebInterfaceMetadata meta, Object bean, Method method) {
        // 1. 比较 Bean 实例和方法签名 (适用于实例注册或已解析的情况)
        if (meta.getTargetBean() == bean) {
            return meta.getTargetMethod().equals(method);
        }

        // 2. 处理延迟加载情况：比较 Bean Name (String)
        if (bean instanceof String) {
            String beanName = (String) bean;
            // 计算注册时用的类名
            Class<?> userClass = AopUtils.getTargetClass(meta.getTargetBean());
            String version = meta.getVersion();
            String expectedBeanName = meta.getLingId() + ":" + (version != null ? version : "unknown")
                    + ":" + userClass.getName();
            if (expectedBeanName.equals(beanName)) {
                // 如果 Bean Name 匹配，还需进一步校验方法名和参数类型
                return meta.getTargetMethod().getName().equals(method.getName()) &&
                        isSameParameterTypes(meta.getTargetMethod(), method);
            }
        }

        // 3. 处理代理或其他复杂情况：降级为方法签名比较
        if (meta.getTargetMethod().getName().equals(method.getName())) {
            return isSameParameterTypes(meta.getTargetMethod(), method);
        }
        return false;
    }

    /**
     * 判断方法参数类型是否一致
     */
    private boolean isSameParameterTypes(Method m1, Method m2) {
        Class<?>[] params1 = m1.getParameterTypes();
        Class<?>[] params2 = m2.getParameterTypes();
        if (params1.length != params2.length) {
            return false;
        }
        for (int i = 0; i < params1.length; i++) {
            if (!params1[i].equals(params2[i])) {
                return false;
            }
        }
        return true;
    }

    private WebInterfaceMetadata resolveByRouteKey(String routeKey, HttpServletRequest request) {
        if (routeKey == null) {
            return null;
        }
        List<WebInterfaceMetadata> metas = metadataMap.get(routeKey);
        if (metas == null || metas.isEmpty()) {
            return null;
        }
        if (metas.size() == 1) {
            return metas.get(0);
        }

        String forcedVersion = request != null ? (String) request.getAttribute(REQUEST_TARGET_VERSION_KEY) : null;
        if (forcedVersion != null) {
            WebInterfaceMetadata matched = resolveByVersion(routeKey, forcedVersion);
            if (matched != null) {
                return matched;
            }
        }

        WebInterfaceMetadata sample = metas.get(0);
        String lingId = sample.getLingId();
        if (lingRepository != null && trafficRouter != null && lingId != null) {
            LingRuntime runtime = lingRepository.getRuntime(lingId);
            if (runtime != null) {
                List<LingInstance> candidates = runtime.getReadyInstances();
                if (candidates != null && !candidates.isEmpty()) {
                    List<LingInstance> filtered = new ArrayList<>();
                    for (LingInstance inst : candidates) {
                        if (resolveByVersion(routeKey, inst.getVersion()) != null) {
                            filtered.add(inst);
                        }
                    }
                    if (!filtered.isEmpty()) {
                        InvocationContext ctx = InvocationContext.obtain();
                        try {
                            ctx.setTargetLingId(lingId);
                            ctx.setServiceFQSID(lingId + ":http");
                            ctx.setRuntime(runtime);
                            LingInstance target = trafficRouter.route(filtered, ctx);
                            if (target != null) {
                                WebInterfaceMetadata matched = resolveByVersion(routeKey, target.getVersion());
                                if (matched != null) {
                                    return matched;
                                }
                            }
                        } finally {
                            ctx.recycle();
                        }
                        // B. 路由未命中时，回退到任意可用版本
                        return metas.get(0);
                    }
                }
            }
        }

        // A. 没有可用运行时或路由失败时，直接返回本路由第一个版本
        return metas.get(0);
    }

    private WebInterfaceMetadata resolveByVersion(String routeKey, String version) {
        if (routeKey == null || version == null) {
            return null;
        }
        List<WebInterfaceMetadata> metas = metadataMap.get(routeKey);
        if (metas == null) {
            return null;
        }
        for (WebInterfaceMetadata meta : metas) {
            if (version.equals(meta.getVersion())) {
                return meta;
            }
        }
        return null;
    }

    public Object dispatch(String routeKey, HttpServletRequest request, HttpServletResponse response) throws Exception {
        WebInterfaceMetadata meta = resolveByRouteKey(routeKey, request);
        if (meta == null) {
            throw new LingException("No available target for route: " + routeKey);
        }
        if (request != null) {
            request.setAttribute(REQUEST_METADATA_KEY, meta);
        }

        ClassLoader original = Thread.currentThread().getContextClassLoader();
        if (meta.getClassLoader() != null) {
            Thread.currentThread().setContextClassLoader(meta.getClassLoader());
        }
        try {
            return invokeTarget(meta, request, response);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private Object invokeTarget(WebInterfaceMetadata meta, HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        HandlerMethod handlerMethod = new HandlerMethod(meta.getTargetBean(), meta.getTargetMethod());
        ServletInvocableHandlerMethod invocable = new ServletInvocableHandlerMethod(meta.getTargetBean(),
                meta.getTargetMethod());
        HandlerMethodArgumentResolverComposite resolvers = getArgumentResolvers();
        if (resolvers != null) {
            invocable.setHandlerMethodArgumentResolvers(resolvers);
        }
        invocable.setParameterNameDiscoverer(getParameterNameDiscoverer());
        invocable.setDataBinderFactory(Objects.requireNonNull(getDataBinderFactory(handlerMethod)));

        ServletWebRequest webRequest = new ServletWebRequest(request, response);
        ModelAndViewContainer mavContainer = new ModelAndViewContainer();
        return invocable.invokeForRequest(webRequest, mavContainer);
    }

    private HandlerMethodArgumentResolverComposite getArgumentResolvers() {
        if (argumentResolvers != null) {
            return argumentResolvers;
        }
        try {
            Field field = ReflectionUtils.findField(RequestMappingHandlerAdapter.class, "argumentResolvers");
            if (field != null) {
                ReflectionUtils.makeAccessible(field);
                argumentResolvers = (HandlerMethodArgumentResolverComposite) ReflectionUtils.getField(field, hostAdapter);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve argumentResolvers from RequestMappingHandlerAdapter", e);
        }
        return argumentResolvers;
    }

    private ParameterNameDiscoverer getParameterNameDiscoverer() {
        if (parameterNameDiscoverer != null) {
            return parameterNameDiscoverer;
        }
        try {
            Field field = ReflectionUtils.findField(RequestMappingHandlerAdapter.class, "parameterNameDiscoverer");
            if (field != null) {
                ReflectionUtils.makeAccessible(field);
                parameterNameDiscoverer = (ParameterNameDiscoverer) ReflectionUtils.getField(field, hostAdapter);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve parameterNameDiscoverer from RequestMappingHandlerAdapter", e);
        }
        return parameterNameDiscoverer;
    }

    private WebDataBinderFactory getDataBinderFactory(HandlerMethod handlerMethod) {
        try {
            Method method = ReflectionUtils.findMethod(RequestMappingHandlerAdapter.class, "getDataBinderFactory",
                    HandlerMethod.class);
            if (method != null) {
                ReflectionUtils.makeAccessible(method);
                return (WebDataBinderFactory) method.invoke(hostAdapter, handlerMethod);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve WebDataBinderFactory", e);
        }
        return null;
    }

    public static class LingWebEntryHandler {
        private final WebInterfaceManager manager;
        private final String routeKey;

        public LingWebEntryHandler(WebInterfaceManager manager, String routeKey) {
            this.manager = manager;
            this.routeKey = routeKey;
        }

        public String getRouteKey() {
            return routeKey;
        }

        @ResponseBody
        public Object dispatch(HttpServletRequest request, HttpServletResponse response) throws Exception {
            return manager.dispatch(routeKey, request, response);
        }
    }

    /**
     * 构建路由键：httpMethod#urlPattern
     */
    private String buildRouteKey(WebInterfaceMetadata metadata) {
        return metadata.getHttpMethod() + "#" + metadata.getUrlPattern();
    }

    /**
     * 反射清理 Adapter 的灵元相关缓存
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
        if (field == null) {
            return;
        }
        ReflectionUtils.makeAccessible(field);
        @SuppressWarnings("unchecked")
        Map<Class<?>, ?> cache = (Map<Class<?>, ?>) ReflectionUtils.getField(field, hostAdapter);
        if (cache != null) {
            cache.keySet().removeIf(clazz -> clazz != null && clazz.getClassLoader() == lingLoader);
        }
    }

    private void clearAdviceCache(String fieldName, ClassLoader lingLoader) throws Exception {
        Field field = ReflectionUtils.findField(hostAdapter.getClass(), fieldName);
        if (field == null) {
            return;
        }
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
