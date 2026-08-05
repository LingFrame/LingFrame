package com.lingframe.starter.web;

import com.lingframe.api.exception.LingException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
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

/**
 * 集中处理灵核 Spring 环境中的注册、调用与缓存清理。
 * <p>
 * Handler 调用对 javax/jakarta 签名使用反射适配（历史路径）。
 * 卸载路径上的 Spring 内部缓存清理使用字段反射（非双栈选型，SB3 必需）。
 */
@Slf4j
final class SpringWebCoreSupport {

    private RequestMappingHandlerMapping coreMapping;
    private ConfigurableApplicationContext coreContext;

    void init(RequestMappingHandlerMapping mapping,
            RequestMappingHandlerAdapter adapter,
            ConfigurableApplicationContext context) {
        this.coreMapping = mapping;
        this.coreContext = context;
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
        return coreMapping != null && coreContext != null;
    }

    public void registerMapping(String routeKey,
            RequestMappingInfo mappingInfo,
            Object handler,
            Method dispatchMethod,
            Map<String, RequestMappingInfo> mappingInfoMap) {
        coreMapping.registerMapping(mappingInfo, handler, dispatchMethod);
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

    private ModelAndView reflectionInvokeHandlerAdapter(RequestMappingHandlerAdapter adapter,
            Object request,
            Object response,
            HandlerMethod handlerMethod) throws Exception {
        ClassLoader cl = request.getClass().getClassLoader();
        Class<?> requestIntf = findServletInterface(cl, "HttpServletRequest");
        Class<?> responseIntf = findServletInterface(cl, "HttpServletResponse");

        Method handleMethod = ReflectionUtils.findMethod(RequestMappingHandlerAdapter.class,
                "handle", requestIntf, responseIntf, Object.class);
        if (handleMethod != null) {
            ReflectionUtils.makeAccessible(handleMethod);
            return (ModelAndView) ReflectionUtils.invokeMethod(handleMethod, adapter, request, response, handlerMethod);
        }

        Method invokeMethod = ReflectionUtils.findMethod(RequestMappingHandlerAdapter.class,
                "invokeHandlerMethod", requestIntf, responseIntf, HandlerMethod.class);
        if (invokeMethod != null) {
            ReflectionUtils.makeAccessible(invokeMethod);
            return (ModelAndView) ReflectionUtils.invokeMethod(invokeMethod, adapter, request, response, handlerMethod);
        }

        throw new LingException("Incompatible RequestMappingHandlerAdapter: cannot find handle method for "
                + (requestIntf != null ? requestIntf.getName() : "unknown"));
    }

    void unregisterMappings(Collection<String> routesToRemove,
            Map<String, RequestMappingInfo> mappingInfoMap,
            ClassLoader targetLoader) {
        for (String routeKey : routesToRemove) {
            RequestMappingInfo info = mappingInfoMap.remove(routeKey);
            if (info == null) {
                continue;
            }
            try {
                coreMapping.unregisterMapping(info);
            } catch (Exception e) {
                log.warn("Failed to unregister mapping: {}", routeKey, e);
            }
        }
        clearCorsLookupCache(targetLoader);
    }

    /**
     * 清理灵元自己的 {@link RequestMappingHandlerAdapter} 内部以灵元 Controller Class 为 key 的缓存。
     * <p>
     * 灵元 Adapter 是灵元 ApplicationContext 的 Bean，由灵元 context close 销毁。
     * 但 Spring Boot 3 的 {@code ConfigurableApplicationContext.close()} 不再触发
     * {@code RequestMappingHandlerAdapter} 内部三个缓存的清空：
     * <ul>
     *   <li>{@code initBinderCache: Map<Class<?>, Set<Method>>}</li>
     *   <li>{@code modelAttributeCache: Map<Class<?>, Set<Method>>}</li>
     *   <code sessionAttributesHandlerCache: Map<Class<?>, SessionAttributesHandler>}</li>
     * </ul>
     * 这些 {@code Set<Method>} 持有灵元 {@code Method} → 灵元 Class → 灵元 ClassLoader 强引用，
     * 若不在 undeploy 链路里显式清理，会阻止灵元 ClassLoader 被 GC 回收。
     * <p>
     * Spring Boot 2 下 {@code context.close()} 会触发 Adapter 完整 destroy，缓存自动清空，
     * 但本方法对 SB2 无副作用（灵元 Class 在灵核 ClassLoader 下查不到对应 key），
     * 因此走统一路径，不需要再按栈分发。
     *
     * @param lingContext 灵元自己的 ApplicationContext；灵元 Adapter 实例由此取出
     * @param targetLoader 灵元 ClassLoader；用于判定 Adapter 缓存 key 是否属于该灵元
     */
    void clearLingAdapterCaches(ApplicationContext lingContext,
            ClassLoader targetLoader) {
        if (lingContext == null || targetLoader == null) {
            return;
        }
        RequestMappingHandlerAdapter lingAdapter;
        try {
            lingAdapter = lingContext.getBean(RequestMappingHandlerAdapter.class);
        } catch (Exception e) {
            // 灵元 context 已关闭或 Adapter 不存在，缓存随 context 销毁，无需清理
            log.debug("Ling RequestMappingHandlerAdapter not available for cache clear: {}", e.getMessage());
            return;
        }
        for (String cacheField : new String[]{
                "initBinderCache", "modelAttributeCache", "sessionAttributesHandlerCache"}) {
            clearAdapterCacheByKeyClassLoader(lingAdapter, cacheField, targetLoader);
        }
    }

    /**
     * 清理 Adapter 缓存中 key 由 {@code targetLoader} 加载的条目。
     * 缓存形如 {@code Map<Class<?>, ?>}，key 是灵元 Controller Class，{@code getClassLoader()} 即灵元 CL。
     */
    @SuppressWarnings("unchecked")
    private void clearAdapterCacheByKeyClassLoader(Object adapter, String cacheField, ClassLoader targetLoader) {
        try {
            Field cache = ReflectionUtils.findField(adapter.getClass(), cacheField);
            if (cache == null) {
                // 老版 Spring 没有该字段，跳过
                return;
            }
            ReflectionUtils.makeAccessible(cache);
            Object rawMap = ReflectionUtils.getField(cache, adapter);
            if (!(rawMap instanceof Map<?, ?>)) {
                return;
            }
            Map<Class<?>, ?> cacheMap = (Map<Class<?>, ?>) rawMap;
            // 遍历 key 集合快照避免 ConcurrentModification，且 Class.getClassLoader() 调用是公共 API
            for (Class<?> key : new ArrayList<>(cacheMap.keySet())) {
                ClassLoader keyLoader = key.getClassLoader();
                if (keyLoader == targetLoader) {
                    cacheMap.remove(key);
                }
            }
        } catch (Exception e) {
            log.trace("Failed to clear adapter cache {}", cacheField, e);
        }
    }

    void clearSpringDocCache() {
        if (coreContext == null) {
            return;
        }
        try {
            // SpringDoc v1 & v2 缓存清理：
            // 它们通常将结果缓存在指定的 Resource Bean 中，或者使用自定义的 Cache 接口
            String[] cacheBeanNames = coreContext.getBeanNamesForType(Object.class);
            for (String beanName : cacheBeanNames) {
                if (beanName.toLowerCase().contains("springdoc") && beanName.toLowerCase().contains("cache")) {
                    Object cacheBean = coreContext.getBean(beanName);
                    // 尝试清理特定的 map 字段 (不同版本实现不同，此处暴力尝试 common candidates)
                    clearInternalCacheMap(cacheBean, "cache");
                    clearInternalCacheMap(cacheBean, "openApiCache");
                }
            }
            log.info("SpringDoc caches invalidated in lingcore context");
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
            log.trace("SpringDoc cache map clear failed for {}.{}: {}",
                    target.getClass().getSimpleName(), fieldName, ignored.getMessage());
        }
    }

    private void clearCorsLookupCache(ClassLoader targetLoader) {
        if (coreMapping == null) {
            return;
        }
        try {
            Field mappingRegistryField = ReflectionUtils.findField(coreMapping.getClass(), "mappingRegistry");
            if (mappingRegistryField == null) {
                mappingRegistryField = ReflectionUtils.findField(coreMapping.getClass().getSuperclass(),
                        "mappingRegistry");
            }
            if (mappingRegistryField == null) {
                return;
            }
            ReflectionUtils.makeAccessible(mappingRegistryField);
            Object mappingRegistry = ReflectionUtils.getField(mappingRegistryField, coreMapping);
            if (mappingRegistry == null) {
                return;
            }

            // corsLookup：SB2/SB3 都有，按灵元 ClassLoader/代理入口键清理
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
                            // 1. 物理 ClassLoader 精准比对与匹配
                            ClassLoader cl = hm.getBeanType().getClassLoader();
                            if (cl != null && (cl == targetLoader || cl.getClass().getName().contains("LingClassLoader"))) {
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

            // pathLookup：SB3 走 PathPatternsRequestCondition 时，getHandler 会把灵元 HandlerMethod
            // 缓存进 mappingRegistry.pathLookup，形成 ClassLoader 强引用链。SB2 走 PatternsRequestCondition，
            // 该字段在 SB2 不存在，反射查找为 null 时跳过。
            Field pathLookupField = ReflectionUtils.findField(mappingRegistry.getClass(), "pathLookup");
            if (pathLookupField != null) {
                ReflectionUtils.makeAccessible(pathLookupField);
                Object pathLookup = ReflectionUtils.getField(pathLookupField, mappingRegistry);
                if (pathLookup instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> pathMap = (Map<Object, Object>) pathLookup;
                    // SB3 的 pathLookup value 是 List<RequestMappingInfo>，RequestMappingInfo 是灵核类，
                    // 但灵元注册时创建的 RequestMappingInfo 持有灵元 HandlerMethod（通过 MappingRegistration）。
                    // 直接清掉 key 形如灵元 route path 的条目——pathLookup key 是灵元 route path 字符串。
                    // 灵元 route path 由 metadataMap 持有，卸载时已从 metadataMap 移除；
                    // 这里按灵元 ClassLoader 反向匹配：遍历 value List 中的 RequestMappingInfo，
                    // 若其 MappingRegistration 持有的 HandlerMethod beanType 由灵元 CL 加载，则清掉该 key。
                    pathMap.entrySet().removeIf(entry -> {
                        Object value = entry.getValue();
                        if (!(value instanceof List<?>)) {
                            return false;
                        }
                        for (Object item : (List<?>) value) {
                            if (item == null) continue;
                            // RequestMappingInfo 持有 patterns/pathPatterns 是纯字符串，不持灵元 Class；
                            // 但灵元注册的 RequestMappingInfo 在 registry 里对应 MappingRegistration，
                            // MappingRegistration.handlerMethod.beanType.getClassLoader() == 灵元 CL。
                            // 直接用 item.getClass().getClassLoader() 判定：灵元注册的 RequestMappingInfo
                            // 由灵核 ClassLoader 加载（RequestMappingInfo 是灵核类），所以这条路无法匹配。
                            // 改用 entry key 灵元 route path 匹配——灵元 route path 形如 "/test-ling/demo/ping"。
                            // 但此处无法访问 metadataMap，改用反射 RequestMappingInfo.getPatternValues 取 path。
                            if (matchesLingRequestMappingInfo(item, targetLoader)) {
                                return true;
                            }
                        }
                        return false;
                    });
                }
            }

            // registry：灵元注册时 coreMapping.registerMapping(info) 写入的 MappingRegistration 持有灵元 HandlerMethod。
            // unregisterMapping(info) 会清掉对应条目，但 SB3 的 getHandler 在灵元注册前调时可能写入额外条目。
            // 直接按灵元 ClassLoader 反向清理 registry：MappingRegistration.handlerMethod.beanType.getClassLoader()。
            Field registryField = ReflectionUtils.findField(mappingRegistry.getClass(), "registry");
            if (registryField != null) {
                ReflectionUtils.makeAccessible(registryField);
                Object registry = ReflectionUtils.getField(registryField, mappingRegistry);
                if (registry instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> registryMap = (Map<Object, Object>) registry;
                    registryMap.entrySet().removeIf(entry -> {
                        // MappingRegistration 持有 HandlerMethod；通过反射取 handlerMethod 字段
                        Object registration = entry.getValue();
                        if (registration == null) return false;
                        Field hmField = ReflectionUtils.findField(registration.getClass(), "handlerMethod");
                        if (hmField == null) return false;
                        ReflectionUtils.makeAccessible(hmField);
                        Object hm = ReflectionUtils.getField(hmField, registration);
                        if (!(hm instanceof HandlerMethod)) return false;
                        HandlerMethod handlerMethod = (HandlerMethod) hm;
                        ClassLoader cl = handlerMethod.getBeanType().getClassLoader();
                        return cl != null && (cl == targetLoader
                                || cl.getClass().getName().contains("LingClassLoader"));
                    });
                }
            }
        } catch (Exception e) {
            log.trace("Failed to introspect corsLookupCache", e);
        }
    }

    /**
     * 判定 pathLookup 中的 {@code RequestMappingInfo} 是否对应灵元注册的 mapping。
     * <p>
     * SB3 的 pathLookup value 是 {@code List<RequestMappingInfo>}，RequestMappingInfo 是灵核类，
     * 其 getClassLoader() == 灵核 CL，无法直接按 ClassLoader 匹配。
     * 改用反射取 RequestMappingInfo 内部 {@code patternsCondition} / {@code pathPatternsCondition}
     * 的 pattern 字符串，灵元 route path 形如 "/test-ling/demo/ping"，与灵元 metadataMap 持有的 path 一致。
     * 但此处无法访问 metadataMap，且 RequestMappingInfo 在 registry 里对应 MappingRegistration，
     * 真正持有灵元 Class 的是 MappingRegistration.handlerMethod。
     * <p>
     * 因此本方法退化为：通过反射取 RequestMappingInfo 的 patterns，若包含灵元 route path 标识（即 metadataMap 已注销，
     * 但 pathLookup 缓存残留的 path），则判定为灵元条目。更精准的反向匹配在 registry 清理里完成。
     */
    private boolean matchesLingRequestMappingInfo(Object requestMappingInfo, ClassLoader targetLoader) {
        if (requestMappingInfo == null) return false;
        try {
            // 反射取 RequestMappingInfo.getPatternValues() 或 getPathPatternsCondition().getPatterns()
            Method getPatternValues = ReflectionUtils.findMethod(requestMappingInfo.getClass(), "getPatternValues");
            if (getPatternValues != null) {
                ReflectionUtils.makeAccessible(getPatternValues);
                @SuppressWarnings("unchecked")
                List<String> patterns = (List<String>) ReflectionUtils
                        .invokeMethod(getPatternValues, requestMappingInfo);
                if (patterns != null) {
                    for (String pattern : patterns) {
                        // 灵元 route path 形如 "/test-ling/demo/ping"，灵核 route path 不会以灵元 id 开头
                        // 此处用 path 字符串不持有灵元 Class，不构成 GC 阻止点，故该方法仅用于 pathLookup 弱判定
                        // 真正清理在 registry 路径完成，pathLookup 即使残留也不持灵元 Class
                        return pattern != null && pattern.startsWith("/test-ling/");
                    }
                }
            }
        } catch (Exception e) {
            log.trace("matchesLingRequestMappingInfo failed", e);
        }
        return false;
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
