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
import java.util.Collection;
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
            log.debug("Ling Bean already registered: {}", beanName);
            return;
        }

        GenericBeanDefinition definition = new GenericBeanDefinition();
        definition.setBeanClass(targetClass);
        definition.setInstanceSupplier(instanceSupplier);
        definition.setScope("singleton");
        applicationContext.registerBeanDefinition(beanName, definition);
        log.info("Registered ling Bean for SpringDoc: {} (Class: {})", beanName, targetClass.getName());
    }

    void registerMapping(String routeKey,
                         RequestMappingInfo mappingInfo,
                         Object handler,
                         Method dispatchMethod,
                         Map<String, RequestMappingInfo> mappingInfoMap) {
        hostMapping.registerMapping(mappingInfo, handler, dispatchMethod);
        mappingInfoMap.put(routeKey, mappingInfo);
    }

    void cleanupCompatibilityArtifacts(Collection<String> beanNamesToRemove,
                                       Collection<String> routesToRemove,
                                       Map<String, RequestMappingInfo> mappingInfoMap,
                                       ClassLoader cleanupLoader) {
        if (hostContext instanceof GenericApplicationContext && beanNamesToRemove != null && !beanNamesToRemove.isEmpty()) {
            cleanupSpringDocBeans((GenericApplicationContext) hostContext, beanNamesToRemove);
        }
        if (routesToRemove != null && !routesToRemove.isEmpty()) {
            unregisterMappings(routesToRemove, mappingInfoMap);
        }
        if (hostAdapter != null && cleanupLoader != null) {
            clearAdapterCaches(cleanupLoader);
        }
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
