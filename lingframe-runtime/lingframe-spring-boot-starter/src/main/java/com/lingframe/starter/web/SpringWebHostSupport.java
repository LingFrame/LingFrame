package com.lingframe.starter.web;

import lombok.extern.slf4j.Slf4j;
import com.lingframe.api.exception.LingException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;

/**
 * 集中处理灵核 Spring 环境中的注册、调用与缓存清理。
 * <p>
 * 🔥 核心设计：Servlet API 无关化 (Decoupled from javax/jakarta)
 * 允许该类在 Spring Boot 2 (javax) 和 Spring Boot 3 (jakarta) 环境下均可编译运行。
 * 所有具体的 Servlet 调用均通过反射动态探测完成。
 */
@Slf4j
final class SpringWebHostSupport {

    private RequestMappingHandlerMapping hostMapping;
    private ConfigurableApplicationContext hostContext;

    void init(RequestMappingHandlerMapping mapping,
            RequestMappingHandlerAdapter adapter,
            ConfigurableApplicationContext context) {
        this.hostMapping = mapping;
        this.hostContext = context;
    }

    public void setApplicationContext(GenericApplicationContext applicationContext) {
        // 用于测试环境下的手动注入或额外扩展
    }

    private static Class<?> findServletInterface(ClassLoader cl, String interfaceName) {
        try {
            return Class.forName("jakarta.servlet.http." + interfaceName, false, cl);
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName("javax.servlet.http." + interfaceName, false, cl);
            } catch (ClassNotFoundException ex) {
                return null;
            }
        }
    }

    boolean isInitialized() {
        return hostMapping != null && hostContext != null;
    }

    public void registerMapping(String routeKey,
            RequestMappingInfo mappingInfo,
            Object handler,
            Method dispatchMethod,
            Map<String, RequestMappingInfo> mappingInfoMap) {
        hostMapping.registerMapping(mappingInfo, handler, dispatchMethod);
        mappingInfoMap.put(routeKey, mappingInfo);
    }

    Object invokeTarget(WebInterfaceMetadata metadata, String routeKey, ServletWebRequest webRequest) throws Exception {
        Object targetBean = requireTargetBean(metadata, routeKey);
        Method targetMethod = requireTargetMethod(metadata, routeKey);

        // 🔥 架构级物理阻断：
        // 绝不能使用灵核的 RequestMappingHandlerAdapter（包括它的 ArgumentResolvers, ReturnValueHandlers 等）
        // 因为它们是灵核单例，会将 HandlerMethod (包含 LingClassLoader) 缓存进内部的 Map 中（如 argumentResolverCache）
        // 改为：从灵元自己的 ApplicationContext 中获取它自己的 RequestMappingHandlerAdapter 处理！
        ApplicationContext lingContext = metadata.getLingApplicationContext();
        if (lingContext == null) {
            throw new LingException("Target ling ApplicationContext is not available for route: " + routeKey);
        }

        RequestMappingHandlerAdapter lingAdapter;
        try {
            lingAdapter = lingContext.getBean(RequestMappingHandlerAdapter.class);
        } catch (Exception e) {
            throw new LingException("Failed to locate RequestMappingHandlerAdapter in ling Context: " + routeKey, e);
        }

        HandlerMethod handlerMethod = new HandlerMethod(targetBean, targetMethod);
        
        // 获取原生 Request/Response 对象 (Object 类型，不假设是 javax 还是 jakarta)
        Object request = webRequest.getNativeRequest();
        Object response = webRequest.getNativeResponse();

        if (response == null) {
            throw new IllegalStateException("Native response is required but null");
        }

        ModelAndView mav = reflectionInvokeHandlerAdapter(lingAdapter, request, response, handlerMethod);
        if (mav != null) {
            return mav.getModel();
        }
        return null;
    }

    /**
     * 动态调用 RequestMappingHandlerAdapter 的处理逻辑。
     * 由于不同版本的 Spring Boot 中 handle 方法的参数分别是 javax.servlet 和 jakarta.servlet，
     * 这里必须使用反射进行多态适配。
     */
    private ModelAndView reflectionInvokeHandlerAdapter(RequestMappingHandlerAdapter adapter,
            Object request,
            Object response,
            HandlerMethod handlerMethod) throws Exception {

        // 确定性探测接口类 (javax.servlet.http.HttpServletRequest 或 jakarta.servlet.http.HttpServletRequest)
        ClassLoader cl = request.getClass().getClassLoader();
        Class<?> requestIntf = findServletInterface(cl, "HttpServletRequest");
        Class<?> responseIntf = findServletInterface(cl, "HttpServletResponse");

        // 1. 优先尝试公共 handle 方法 (最稳固)
        // 方法签名: handle(HttpServletRequest, HttpServletResponse, Object)
        Method handleMethod = ReflectionUtils.findMethod(RequestMappingHandlerAdapter.class,
                "handle", requestIntf, responseIntf, Object.class);

        if (handleMethod != null) {
            ReflectionUtils.makeAccessible(handleMethod);
            return (ModelAndView) ReflectionUtils.invokeMethod(handleMethod, adapter, request, response, handlerMethod);
        }

        // 2. 尝试调用内部封装的 invokeHandlerMethod (针对较老或较特殊的 Spring 版本)
        Method invokeMethod = ReflectionUtils.findMethod(RequestMappingHandlerAdapter.class,
                "invokeHandlerMethod", requestIntf, responseIntf, HandlerMethod.class);
        if (invokeMethod != null) {
            ReflectionUtils.makeAccessible(invokeMethod);
            return (ModelAndView) ReflectionUtils.invokeMethod(invokeMethod, adapter, request, response, handlerMethod);
        }

        throw new LingException("Incompatible RequestMappingHandlerAdapter: cannot find handle method for "
                + requestIntf.getName());
    }

    void unregisterMappings(Collection<String> routesToRemove,
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

    void clearSpringDocCache() {
        if (hostContext == null) {
            return;
        }
        try {
            // SpringDoc v1 & v2 缓存清理：
            // 它们通常将结果缓存在指定的 Resource Bean 中，或者使用自定义的 Cache 接口
            String[] cacheBeanNames = hostContext.getBeanNamesForType(Object.class);
            for (String beanName : cacheBeanNames) {
                if (beanName.toLowerCase().contains("springdoc") && beanName.toLowerCase().contains("cache")) {
                    Object cacheBean = hostContext.getBean(beanName);
                    // 尝试清理特定的 map 字段 (不同版本实现不同，此处暴力尝试 common candidates)
                    clearInternalCacheMap(cacheBean, "cache");
                    clearInternalCacheMap(cacheBean, "openApiCache");
                }
            }
            log.info("SpringDoc caches invalidated in host context");
        } catch (Exception e) {
            log.debug("Found no SpringDoc cache to clear or clear failed: {}", e.getMessage());
        }
    }

    private void clearInternalCacheMap(Object target, String fieldName) {
        try {
            Field field = ReflectionUtils.findField(target.getClass(), fieldName);
            if (field != null) {
                ReflectionUtils.makeAccessible(field);
                Object map = ReflectionUtils.getField(field, target);
                if (map instanceof Map) {
                    ((Map<?, ?>) map).clear();
                    log.debug("Cleared SpringDoc cache map: {}.{}", target.getClass().getSimpleName(), fieldName);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void clearCorsLookupCache() {
        if (hostMapping == null) {
            return;
        }
        try {
            Field mappingRegistryField = ReflectionUtils.findField(hostMapping.getClass(), "mappingRegistry");
            if (mappingRegistryField == null) {
                mappingRegistryField = ReflectionUtils.findField(hostMapping.getClass().getSuperclass(),
                        "mappingRegistry");
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
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> corsMap = (Map<Object, Object>) corsLookup;
                    corsMap.keySet().removeIf(key -> {
                        if (key instanceof HandlerMethod) {
                            HandlerMethod hm = (HandlerMethod) key;
                            // 1. 物理 ClassLoader 匹配：识别灵元加载的类
                            ClassLoader cl = hm.getBeanType().getClassLoader();
                            if (cl != null && cl.getClass().getName().contains("LingClassLoader")) {
                                return true;
                            }
                            // 2. 代理入口匹配
                            Object bean = hm.getBean();
                            if (bean != null && bean.getClass().getName().contains("LingWebEntryHandler")) {
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

    // 所有 clearAdapterCaches 及 getArgumentResolvers 等灵核 Adapter 反射相关方法已被删除，
    // 因为现在使用的是灵元内部独立的 RequestMappingHandlerAdapter，不会产生灵核缓存污染。
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
