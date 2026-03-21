package com.lingframe.starter.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.support.AbstractBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.support.WebBindingInitializer;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.HandlerMethodArgumentResolverComposite;
import org.springframework.web.method.support.HandlerMethodReturnValueHandlerComposite;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 集中处理宿主 Spring 环境中的注册、调用与缓存清理。
 */
@Slf4j
final class SpringWebHostSupport {

    private RequestMappingHandlerMapping hostMapping;
    private RequestMappingHandlerAdapter hostAdapter;
    private ConfigurableApplicationContext hostContext;

    private volatile HandlerMethodArgumentResolverComposite argumentResolvers;
    private volatile ParameterNameDiscoverer parameterNameDiscoverer;
    private volatile HandlerMethodReturnValueHandlerComposite returnValueHandlers;
    private volatile WebBindingInitializer webBindingInitializer;

    void init(RequestMappingHandlerMapping mapping,
              RequestMappingHandlerAdapter adapter,
              ConfigurableApplicationContext context) {
        this.hostMapping = mapping;
        this.hostAdapter = adapter;
        this.hostContext = context;
    }

    boolean isInitialized() {
        return hostMapping != null && hostContext != null;
    }

    void registerSpringDocBean(String beanName, Class<?> targetClass, Supplier<Object> instanceSupplier) {
        if (!(hostContext instanceof GenericApplicationContext) || beanName == null || beanName.isEmpty()) {
            return;
        }

        GenericApplicationContext applicationContext = (GenericApplicationContext) hostContext;
        if (applicationContext.containsBeanDefinition(beanName)) {
            registerAdditionalSpringDocController(targetClass);
            syncSpringDocMappings(beanName, instanceSupplier);
            log.debug("Ling Bean already registered: {}", beanName);
            return;
        }

        GenericBeanDefinition definition = new GenericBeanDefinition();
        definition.setBeanClass(targetClass);
        definition.setInstanceSupplier(instanceSupplier);
        definition.setScope("singleton");
        applicationContext.registerBeanDefinition(beanName, definition);
        registerAdditionalSpringDocController(targetClass);
        syncSpringDocMappings(beanName, instanceSupplier);
        log.info("Registered ling Bean for SpringDoc: {} (Class: {})", beanName, targetClass.getName());
    }

    void registerMapping(String routeKey,
                         RequestMappingInfo mappingInfo,
                         Object handler,
                         Method dispatchMethod,
                         Map<String, RequestMappingInfo> mappingInfoMap) {
        hostMapping.registerMapping(mappingInfo, handler, dispatchMethod);
        mappingInfoMap.put(routeKey, mappingInfo);
        refreshSpringDocCaches();
    }

    void cleanupCompatibilityArtifacts(Collection<String> beanNamesToRemove,
                                       Collection<String> routesToRemove,
                                       Map<String, RequestMappingInfo> mappingInfoMap,
                                       ClassLoader cleanupLoader,
                                       Collection<Class<?>> controllerClassesToRemove) {
        if (hostContext instanceof GenericApplicationContext && beanNamesToRemove != null && !beanNamesToRemove.isEmpty()) {
            cleanupSpringDocBeans((GenericApplicationContext) hostContext, beanNamesToRemove);
        }
        if (controllerClassesToRemove != null && !controllerClassesToRemove.isEmpty()) {
            removeAdditionalSpringDocControllers(controllerClassesToRemove);
        }
        if (beanNamesToRemove != null && !beanNamesToRemove.isEmpty()) {
            removeSpringDocMappings(beanNamesToRemove);
        }
        if (routesToRemove != null && !routesToRemove.isEmpty()) {
            unregisterMappings(routesToRemove, mappingInfoMap);
        }
        if (hostAdapter != null && cleanupLoader != null) {
            clearAdapterCaches(cleanupLoader);
        }
        refreshSpringDocCaches();
    }

    Object invokeTarget(WebInterfaceMetadata metadata, String routeKey, ServletWebRequest webRequest) throws Exception {
        Object targetBean = requireTargetBean(metadata, routeKey);
        Method targetMethod = requireTargetMethod(metadata, routeKey);

        HandlerMethod handlerMethod = new HandlerMethod(targetBean, targetMethod);
        ServletInvocableHandlerMethod invocable = new ServletInvocableHandlerMethod(targetBean, targetMethod);
        HandlerMethodArgumentResolverComposite resolvers = getArgumentResolvers();
        if (resolvers != null) {
            invocable.setHandlerMethodArgumentResolvers(resolvers);
        }
        HandlerMethodReturnValueHandlerComposite returnHandlers = getReturnValueHandlers();
        if (returnHandlers != null) {
            invocable.setHandlerMethodReturnValueHandlers(returnHandlers);
        }
        WebBindingInitializer bindingInitializer = getWebBindingInitializer();
        if (bindingInitializer != null) {
            invokeWebBindingInitializerIfSupported(invocable, bindingInitializer);
        }
        invocable.setParameterNameDiscoverer(getParameterNameDiscoverer());
        invocable.setDataBinderFactory(Objects.requireNonNull(getDataBinderFactory(handlerMethod)));

        ModelAndViewContainer mavContainer = new ModelAndViewContainer();
        invocable.invokeAndHandle(webRequest, mavContainer);
        if (mavContainer.isRequestHandled()) {
            return null;
        }
        if (mavContainer.getViewName() != null) {
            return new ModelAndView(mavContainer.getViewName(), mavContainer.getModel());
        }
        return mavContainer.getModel();
    }

    private void cleanupSpringDocBeans(GenericApplicationContext applicationContext, Collection<String> beanNames) {
        DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) applicationContext.getBeanFactory();
        for (String beanName : beanNames) {
            if (!applicationContext.containsBeanDefinition(beanName)) {
                continue;
            }
            try {
                if (beanFactory.containsSingleton(beanName)) {
                    beanFactory.destroySingleton(beanName);
                }
                if (beanFactory.containsBeanDefinition(beanName)) {
                    beanFactory.removeBeanDefinition(beanName);
                }
                beanFactory.clearMetadataCache();
                log.debug("Cleaned up bean: {}", beanName);
            } catch (Exception e) {
                log.warn("Failed to cleanup bean: {}", beanName, e);
            }
        }
        clearMergedBeanDefinitions(applicationContext, beanNames);
    }

    private void syncSpringDocMappings(String beanName, Supplier<Object> instanceSupplier) {
        if (beanName == null || beanName.isEmpty() || instanceSupplier == null) {
            return;
        }
        Object controller;
        try {
            controller = instanceSupplier.get();
        } catch (Exception e) {
            log.debug("Failed to obtain SpringDoc controller instance for bean {}", beanName, e);
            return;
        }
        if (controller == null) {
            return;
        }

        Map<String, Object> mapping = Collections.singletonMap(beanName, controller);
        for (Object openApiService : resolveOpenApiServices()) {
            try {
                Method addMappings = ReflectionUtils.findMethod(openApiService.getClass(), "addMappings", Map.class);
                if (addMappings == null) {
                    continue;
                }
                ReflectionUtils.makeAccessible(addMappings);
                ReflectionUtils.invokeMethod(addMappings, openApiService, mapping);
                clearSpringDocCachedOpenApi(openApiService);
            } catch (Exception e) {
                log.debug("Failed to sync SpringDoc mappings for bean {}", beanName, e);
            }
        }
    }

    private void registerAdditionalSpringDocController(Class<?> targetClass) {
        if (targetClass == null) {
            return;
        }
        updateAdditionalSpringDocControllers(targetClass, true);
    }

    private void removeSpringDocMappings(Collection<String> beanNames) {
        if (beanNames == null || beanNames.isEmpty()) {
            return;
        }
        for (Object openApiService : resolveOpenApiServices()) {
            try {
                Map<String, Object> mappings = extractSpringDocMappings(openApiService);
                if (mappings == null || mappings.isEmpty()) {
                    continue;
                }
                for (String beanName : beanNames) {
                    mappings.remove(beanName);
                }
                clearSpringDocCachedOpenApi(openApiService);
            } catch (Exception e) {
                log.debug("Failed to remove SpringDoc mappings for beans {}", beanNames, e);
            }
        }
    }

    private void removeAdditionalSpringDocControllers(Collection<Class<?>> controllerClasses) {
        if (controllerClasses == null || controllerClasses.isEmpty()) {
            return;
        }
        for (Class<?> controllerClass : controllerClasses) {
            if (controllerClass != null) {
                updateAdditionalSpringDocControllers(controllerClass, false);
            }
        }
    }

    private void refreshSpringDocCaches() {
        clearSpringDocProviderHandlerMethodCaches();
        clearSpringDocCachedOpenApis();
    }

    private void clearSpringDocProviderHandlerMethodCaches() {
        if (hostContext == null) {
            return;
        }
        try {
            ClassLoader loader = hostContext.getClassLoader();
            if (loader == null) {
                loader = SpringWebHostSupport.class.getClassLoader();
            }
            Class<?> providerClass = Class.forName("org.springdoc.core.providers.SpringWebProvider", false, loader);
            Field handlerMethodsField = ReflectionUtils.findField(providerClass, "handlerMethods");
            if (handlerMethodsField == null) {
                return;
            }
            ReflectionUtils.makeAccessible(handlerMethodsField);
            for (Object provider : hostContext.getBeansOfType(providerClass).values()) {
                ReflectionUtils.setField(handlerMethodsField, provider, null);
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Exception e) {
            log.debug("Failed to clear SpringDoc handler method caches", e);
        }
    }

    private void clearSpringDocCachedOpenApis() {
        for (Object openApiService : resolveOpenApiServices()) {
            try {
                clearSpringDocCachedOpenApi(openApiService);
            } catch (Exception e) {
                log.debug("Failed to clear SpringDoc OpenAPI cache", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractSpringDocMappings(Object openApiService) {
        Method getter = ReflectionUtils.findMethod(openApiService.getClass(), "getMappingsMap");
        if (getter == null) {
            return null;
        }
        ReflectionUtils.makeAccessible(getter);
        Object result = ReflectionUtils.invokeMethod(getter, openApiService);
        return result instanceof Map ? (Map<String, Object>) result : null;
    }

    private Collection<Object> resolveOpenApiServices() {
        if (hostContext == null) {
            return Collections.emptyList();
        }
        List<Object> services = new ArrayList<>();
        collectOpenApiServices(services, "org.springdoc.core.OpenAPIService");
        collectOpenApiServices(services, "org.springdoc.core.service.OpenAPIService");
        return services;
    }

    private void collectOpenApiServices(List<Object> services, String className) {
        try {
            ClassLoader loader = hostContext.getClassLoader();
            if (loader == null) {
                loader = SpringWebHostSupport.class.getClassLoader();
            }
            Class<?> serviceClass = Class.forName(className, false, loader);
            services.addAll(hostContext.getBeansOfType(serviceClass).values());
        } catch (ClassNotFoundException ignored) {
        } catch (Exception e) {
            log.debug("Failed to resolve SpringDoc OpenAPIService {}", className, e);
        }
    }

    @SuppressWarnings("unchecked")
    private void clearSpringDocCachedOpenApi(Object openApiService) {
        Field cacheField = ReflectionUtils.findField(openApiService.getClass(), "cachedOpenAPI");
        if (cacheField == null) {
            return;
        }
        ReflectionUtils.makeAccessible(cacheField);
        Object cache = ReflectionUtils.getField(cacheField, openApiService);
        if (cache instanceof Map<?, ?>) {
            ((Map<Object, Object>) cache).clear();
        }
    }

    @SuppressWarnings("unchecked")
    private void updateAdditionalSpringDocControllers(Class<?> targetClass, boolean add) {
        if (hostContext == null || targetClass == null) {
            return;
        }
        try {
            ClassLoader loader = hostContext.getClassLoader();
            if (loader == null) {
                loader = SpringWebHostSupport.class.getClassLoader();
            }
            Class<?> resourceClass = Class.forName("org.springdoc.api.AbstractOpenApiResource", false, loader);
            if (add) {
                Method addMethod = ReflectionUtils.findMethod(resourceClass, "addRestControllers", Class[].class);
                if (addMethod == null) {
                    return;
                }
                ReflectionUtils.makeAccessible(addMethod);
                ReflectionUtils.invokeMethod(addMethod, null, new Object[]{new Class<?>[]{targetClass}});
                return;
            }
            Field field = ReflectionUtils.findField(resourceClass, "ADDITIONAL_REST_CONTROLLERS");
            if (field == null) {
                return;
            }
            ReflectionUtils.makeAccessible(field);
            Object value = ReflectionUtils.getField(field, null);
            if (value instanceof Set<?>) {
                ((Set<Class<?>>) value).remove(targetClass);
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Exception e) {
            log.debug("Failed to update SpringDoc additional rest controllers for {}", targetClass.getName(), e);
        }
    }

    private void unregisterMappings(Collection<String> routesToRemove,
                                    Map<String, RequestMappingInfo> mappingInfoMap) {
        for (String routeKey : routesToRemove) {
            RequestMappingInfo info = mappingInfoMap.remove(routeKey);
            if (info == null) {
                continue;
            }
            try {
                hostMapping.unregisterMapping(info);
            } catch (Exception e) {
                log.warn("Failed to unregister mapping: {}", routeKey, e);
            }
        }
        clearCorsLookupCache();
    }

    private void clearCorsLookupCache() {
        if (hostMapping == null) {
            return;
        }
        try {
            Field mappingRegistryField = ReflectionUtils.findField(hostMapping.getClass(), "mappingRegistry");
            if (mappingRegistryField == null) {
                // Try abstract class
                mappingRegistryField = ReflectionUtils.findField(hostMapping.getClass().getSuperclass(), "mappingRegistry");
            }
            if (mappingRegistryField == null) {
                return;
            }
            ReflectionUtils.makeAccessible(mappingRegistryField);
            Object mappingRegistry = ReflectionUtils.getField(mappingRegistryField, hostMapping);
            if (mappingRegistry == null) {
                return;
            }

            Field corsLookupField = ReflectionUtils.findField(mappingRegistry.getClass(), "corsLookup");
            if (corsLookupField != null) {
                ReflectionUtils.makeAccessible(corsLookupField);
                Object corsLookup = ReflectionUtils.getField(corsLookupField, mappingRegistry);
                if (corsLookup instanceof Map<?, ?>) {
                    // Check if HandlerMethod matches our ClassLoader
                    // Since corsLookup doesn't necessarily hold classloader references if the HandlerMethod is already removed,
                    // we can't easily filter it. But unregisterMapping doesn't clear CORS cache in older Springs.
                    // Fortunately, Spring 5.3+ clears corsLookupCache in unregisterMapping. But just in case,
                    // we can try to clean it if needed. However, scanning corsLookupCache requires iterating.
                    // We only need to remove entries whose handler method class loader belongs to the ling loader.
                    // Wait, corsLookupCache keys might be HandlerMethod.
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> corsMap = (Map<Object, Object>) corsLookup;
                    corsMap.keySet().removeIf(key -> {
                        // 移除所有的 HandlerMethod 如果它们看起来是从非宿主加载器加载的
                        // （在未来的改进中，我们可以通过 cleanupLoader 真正过滤）
                        if (key instanceof HandlerMethod) {
                            Class<?> beanType = ((HandlerMethod) key).getBeanType();
                            ClassLoader cl = beanType.getClassLoader();
                            if (cl != null && cl != WebInterfaceManager.class.getClassLoader() && 
                                cl.getClass().getName().contains("LingClassLoader")) {
                                return true;
                            }
                        }
                        return false;
                    });
                }
            }
        } catch (Exception e) {
            log.trace("Failed to introspect corsLookupCache", e);
        }
    }

    private void clearMergedBeanDefinitions(GenericApplicationContext applicationContext,
                                            Collection<String> beanNames) {
        try {
            Field mergedField = ReflectionUtils.findField(AbstractBeanFactory.class, "mergedBeanDefinitions");
            if (mergedField != null) {
                ReflectionUtils.makeAccessible(mergedField);
                @SuppressWarnings("unchecked")
                Map<String, ?> mergedBeanDefinitions =
                        (Map<String, ?>) ReflectionUtils.getField(mergedField, applicationContext.getBeanFactory());
                if (mergedBeanDefinitions != null) {
                    for (String beanName : beanNames) {
                        mergedBeanDefinitions.remove(beanName);
                        log.debug("Removed mergedBeanDefinition: {}", beanName);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to clear mergedBeanDefinitions, falling back to clearMetadataCache", e);
            applicationContext.getBeanFactory().clearMetadataCache();
        }
    }

    private HandlerMethodArgumentResolverComposite getArgumentResolvers() {
        if (argumentResolvers != null) {
            return argumentResolvers;
        }
        try {
            Field field = ReflectionUtils.findField(RequestMappingHandlerAdapter.class, "argumentResolvers");
            if (field != null) {
                ReflectionUtils.makeAccessible(field);
                argumentResolvers =
                        (HandlerMethodArgumentResolverComposite) ReflectionUtils.getField(field, hostAdapter);
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

    private HandlerMethodReturnValueHandlerComposite getReturnValueHandlers() {
        if (returnValueHandlers != null) {
            return returnValueHandlers;
        }
        try {
            Field field = ReflectionUtils.findField(RequestMappingHandlerAdapter.class, "returnValueHandlers");
            if (field != null) {
                ReflectionUtils.makeAccessible(field);
                returnValueHandlers =
                        (HandlerMethodReturnValueHandlerComposite) ReflectionUtils.getField(field, hostAdapter);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve returnValueHandlers from RequestMappingHandlerAdapter", e);
        }
        return returnValueHandlers;
    }

    private WebBindingInitializer getWebBindingInitializer() {
        if (webBindingInitializer != null) {
            return webBindingInitializer;
        }
        try {
            Field field = ReflectionUtils.findField(RequestMappingHandlerAdapter.class, "webBindingInitializer");
            if (field != null) {
                ReflectionUtils.makeAccessible(field);
                webBindingInitializer = (WebBindingInitializer) ReflectionUtils.getField(field, hostAdapter);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve webBindingInitializer from RequestMappingHandlerAdapter", e);
        }
        return webBindingInitializer;
    }

    private void invokeWebBindingInitializerIfSupported(ServletInvocableHandlerMethod invocable,
                                                        WebBindingInitializer initializer) {
        try {
            Method method = ReflectionUtils.findMethod(
                    invocable.getClass(), "setWebBindingInitializer", WebBindingInitializer.class);
            if (method != null) {
                ReflectionUtils.makeAccessible(method);
                method.invoke(invocable, initializer);
            }
        } catch (Exception e) {
            log.debug("setWebBindingInitializer not supported on this Spring version");
        }
    }

    private WebDataBinderFactory getDataBinderFactory(HandlerMethod handlerMethod) {
        try {
            Method method = ReflectionUtils.findMethod(
                    RequestMappingHandlerAdapter.class, "getDataBinderFactory", HandlerMethod.class);
            if (method != null) {
                ReflectionUtils.makeAccessible(method);
                return (WebDataBinderFactory) method.invoke(hostAdapter, handlerMethod);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve WebDataBinderFactory", e);
        }
        return null;
    }

    private void clearAdapterCaches(ClassLoader lingLoader) {
        try {
            clearCache("sessionAttributesHandlerCache", lingLoader);
            clearCache("initBinderCache", lingLoader);
            clearCache("modelAttributeCache", lingLoader);
            clearAdviceCache("initBinderAdviceCache", lingLoader);
            clearAdviceCache("modelAttributeAdviceCache", lingLoader);
            clearArgumentResolverCache(lingLoader);
            clearReturnValueHandlerCache(lingLoader);
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
        Map<ControllerAdviceBean, Set<Method>> cache =
                (Map<ControllerAdviceBean, Set<Method>>) ReflectionUtils.getField(field, hostAdapter);
        if (cache != null) {
            cache.keySet().removeIf(advice -> {
                Class<?> type = advice.getBeanType();
                return type != null && type.getClassLoader() == lingLoader;
            });
        }
    }

    private void clearArgumentResolverCache(ClassLoader lingLoader) throws Exception {
        HandlerMethodArgumentResolverComposite resolvers = getArgumentResolvers();
        if (resolvers == null) {
            return;
        }

        Field cacheField = ReflectionUtils.findField(
                HandlerMethodArgumentResolverComposite.class, "argumentResolverCache");
        if (cacheField == null) {
            return;
        }
        ReflectionUtils.makeAccessible(cacheField);
        Object cacheObj = ReflectionUtils.getField(cacheField, resolvers);
        if (!(cacheObj instanceof Map<?, ?>)) {
            return;
        }

        @SuppressWarnings("unchecked")
        Map<Object, Object> cache = (Map<Object, Object>) cacheObj;
        int before = cache.size();
        cache.entrySet().removeIf(entry -> isMethodParameterFromClassLoader(entry.getKey(), lingLoader));
        int removed = before - cache.size();
        if (removed > 0) {
            log.debug("Cleared argumentResolverCache: removed {} entries", removed);
        }
    }

    private boolean isMethodParameterFromClassLoader(Object key, ClassLoader lingLoader) {
        if (key == null || lingLoader == null) {
            return false;
        }
        if (key instanceof MethodParameter) {
            Class<?> containing = ((MethodParameter) key).getContainingClass();
            return containing != null && containing.getClassLoader() == lingLoader;
        }
        return false;
    }

    private void clearReturnValueHandlerCache(ClassLoader lingLoader) throws Exception {
        HandlerMethodReturnValueHandlerComposite returnHandlers = getReturnValueHandlers();
        if (returnHandlers == null) {
            return;
        }

        Field cacheField = ReflectionUtils.findField(
                HandlerMethodReturnValueHandlerComposite.class, "returnValueHandlerCache");
        if (cacheField == null) {
            return;
        }
        ReflectionUtils.makeAccessible(cacheField);
        Object cacheObj = ReflectionUtils.getField(cacheField, returnHandlers);
        if (!(cacheObj instanceof Map<?, ?>)) {
            return;
        }

        @SuppressWarnings("unchecked")
        Map<Object, Object> cache = (Map<Object, Object>) cacheObj;
        int before = cache.size();
        cache.entrySet().removeIf(entry -> isMethodParameterFromClassLoader(entry.getKey(), lingLoader));
        int removed = before - cache.size();
        if (removed > 0) {
            log.debug("Cleared returnValueHandlerCache: removed {} entries", removed);
        }
    }

    private Object requireTargetBean(WebInterfaceMetadata metadata, String routeKey) {
        Object targetBean = metadata.getTargetBean();
        if (targetBean == null) {
            throw new IllegalStateException("Target bean no longer available for route: " + routeKey);
        }
        return targetBean;
    }

    private Method requireTargetMethod(WebInterfaceMetadata metadata, String routeKey) {
        Method targetMethod = metadata.getTargetMethod();
        if (targetMethod == null) {
            throw new IllegalStateException("Target method no longer available for route: " + routeKey);
        }
        return targetMethod;
    }
}
