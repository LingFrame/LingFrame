package com.lingframe.starter.web;

import com.lingframe.api.exception.LingException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.TrafficRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.annotation.PreDestroy;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 负责灵元 Controller 的 Web 接口注册、路由解析与调用分发。
 */
@Slf4j
public class WebInterfaceManager implements WebRouteResolver {

    private final Map<String, List<WebInterfaceMetadata>> metadataMap = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> routePatternsByMethod = new ConcurrentHashMap<>();
    private final Map<String, RequestMappingInfo> mappingInfoMap = new ConcurrentHashMap<>();
    private final Map<String, String> compatibilityBeanNamesByRoute = new ConcurrentHashMap<>();

    private final ExecutorService registryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "LingFrame-WebInterfaceManager");
        thread.setDaemon(true);
        thread.setContextClassLoader(WebInterfaceManager.class.getClassLoader());
        return thread;
    });

    private final DefaultWebRouteResolver routeResolver;
    private final SpringWebHostSupport hostSupport = new SpringWebHostSupport();

    public static final String REQUEST_METADATA_KEY = "ling.web.metadata";
    public static final String REQUEST_ROUTE_RESOLUTION_KEY = "ling.web.route.resolution";
    public static final String REQUEST_TARGET_VERSION_KEY = "ling.target.version";

    public WebInterfaceManager(LingRepository lingRepository, TrafficRouter trafficRouter) {
        this.routeResolver = new DefaultWebRouteResolver(
                metadataMap, routePatternsByMethod, lingRepository, trafficRouter);
    }

    public void init(RequestMappingHandlerMapping mapping,
                     RequestMappingHandlerAdapter adapter,
                     ConfigurableApplicationContext hostContext) {
        this.hostSupport.init(mapping, adapter, hostContext);
        log.info("LingFrame WebInterfaceManager initialized with native registration");
    }

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

    public void unregister(String lingId, ClassLoader targetLoader) {
        registryExecutor.execute(() -> unregisterInternal(lingId, targetLoader));
    }

    public void unregisterSync(String lingId, ClassLoader targetLoader) {
        try {
            registryExecutor.submit(() -> unregisterInternal(lingId, targetLoader)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LingException("Interrupted while unregistering web mapping", e);
        } catch (ExecutionException e) {
            throw new LingException("Failed to unregister web mapping", e.getCause());
        }
    }

    private void registerInternal(WebInterfaceMetadata metadata, boolean throwOnError) {
        if (!hostSupport.isInitialized()) {
            log.warn("WebInterfaceManager not initialized, skipping registration: {}", metadata.getUrlPattern());
            return;
        }

        String routeKey = buildRouteKey(metadata);

        try {
            Method targetMethod = requireTargetMethod(metadata, routeKey);
            Class<?> targetClass = metadata.getTargetClass();
            if (targetClass == null) {
                targetClass = targetMethod.getDeclaringClass();
            }
            String version = metadata.getVersion();
            String proxyBeanName = metadata.getLingId() + ":" + (version != null ? version : "unknown")
                    + ":" + targetClass.getName();
            metadata.setSpringDocBeanName(proxyBeanName);
            metadata.minimizeHostReferences();
            hostSupport.registerSpringDocBean(proxyBeanName, targetClass, () -> requireTargetBean(metadata, routeKey));

            RequestMappingInfo info = resolveRequestMappingInfo(metadata);

            if (!mappingInfoMap.containsKey(routeKey)) {
                hostSupport.registerMapping(routeKey, info, proxyBeanName, targetMethod, mappingInfoMap);
                compatibilityBeanNamesByRoute.put(routeKey, proxyBeanName);
                log.info("Registered SpringDoc compatibility mapping: {} {}", metadata.getHttpMethod(),
                        metadata.getUrlPattern());
            }

            metadataMap.compute(routeKey, (key, existing) -> mergeRouteMetadata(routeKey, existing, metadata));
            routePatternsByMethod
                    .computeIfAbsent(metadata.getHttpMethod(), key -> ConcurrentHashMap.newKeySet())
                    .add(metadata.getUrlPattern());

            log.info("Registered: {} {} -> {}.{}",
                    metadata.getHttpMethod(), metadata.getUrlPattern(), metadata.getLingId(), targetMethod.getName());
        } catch (Exception e) {
            if (throwOnError) {
                throw new LingException(
                        "Failed to register web mapping: " + metadata.getHttpMethod() + " " + metadata.getUrlPattern(),
                        e);
            }
            log.error("Failed to register web mapping: {} {}", metadata.getHttpMethod(), metadata.getUrlPattern(), e);
        }
    }

    private List<WebInterfaceMetadata> mergeRouteMetadata(String routeKey,
                                                          List<WebInterfaceMetadata> existing,
                                                          WebInterfaceMetadata incoming) {
        List<WebInterfaceMetadata> merged = existing != null ? new ArrayList<>(existing) : new ArrayList<>();
        for (WebInterfaceMetadata current : merged) {
            if (!Objects.equals(current.getVersion(), incoming.getVersion())) {
                continue;
            }
            if (!current.hasSameTargetSignature(incoming)) {
                throw new IllegalStateException(
                        "Conflicting web route registration for " + routeKey + " version " + incoming.getVersion());
            }
        }
        merged.removeIf(current -> Objects.equals(current.getVersion(), incoming.getVersion())
                && current.hasSameTargetSignature(incoming));
        merged.add(incoming);
        return merged;
    }

    private void unregisterInternal(String lingId, ClassLoader targetLoader) {
        if (!hostSupport.isInitialized()) {
            return;
        }

        log.info("Unregistering interfaces for ling: {} (ClassLoader: {})",
                lingId, targetLoader != null ? targetLoader.hashCode() : "ALL");

        List<String> routesToRemove = new ArrayList<>();
        AtomicReference<ClassLoader> lingLoader = new AtomicReference<>();
        Set<String> beanNamesToRemove = new LinkedHashSet<>();
        Set<Class<?>> controllerClassesToRemove = new LinkedHashSet<>();
        Map<String, List<WebInterfaceMetadata>> remainingMap = new HashMap<>();
        List<WebInterfaceMetadata> removedMetas = new ArrayList<>();
        Set<String> routesToRefresh = new LinkedHashSet<>();

        metadataMap.forEach((routeKey, metas) -> {
            if (metas == null || metas.isEmpty()) {
                return;
            }
            List<WebInterfaceMetadata> remaining = new ArrayList<>();
            String compatibilityBeanName = compatibilityBeanNamesByRoute.get(routeKey);
            boolean compatibilityBeanRemoved = false;
            for (WebInterfaceMetadata meta : metas) {
                // ClassLoader WeakRef 被 GC 后返回 null，也应视为匹配并移除，避免路由残留
                ClassLoader metaLoader = meta.getClassLoader();
                boolean loaderMatches = targetLoader == null
                        || metaLoader == targetLoader
                        || metaLoader == null;
                if (meta.getLingId().equals(lingId) && loaderMatches) {
                    removedMetas.add(meta);
                    if (metaLoader != null) {
                        lingLoader.set(metaLoader);
                    }
                    Class<?> targetClass = meta.getTargetClass();
                    if (targetClass != null) {
                        controllerClassesToRemove.add(targetClass);
                    }
                    if (meta.getSpringDocBeanName() != null) {
                        beanNamesToRemove.add(meta.getSpringDocBeanName());
                        if (Objects.equals(meta.getSpringDocBeanName(), compatibilityBeanName)) {
                            compatibilityBeanRemoved = true;
                        }
                    }
                } else {
                    remaining.add(meta);
                }
            }

            if (remaining.isEmpty()) {
                routesToRemove.add(routeKey);
            } else {
                remainingMap.put(routeKey, remaining);
                if (compatibilityBeanRemoved) {
                    routesToRefresh.add(routeKey);
                }
            }
        });

        for (Map.Entry<String, List<WebInterfaceMetadata>> entry : remainingMap.entrySet()) {
            metadataMap.put(entry.getKey(), entry.getValue());
        }
        for (String routeKey : routesToRemove) {
            metadataMap.remove(routeKey);
        }
        rebuildRoutePatternIndex();

        for (WebInterfaceMetadata meta : removedMetas) {
            meta.clearReferences();
        }

        controllerClassesToRemove.removeIf(this::isControllerClassStillRegistered);
        ClassLoader cleanupLoader = targetLoader != null ? targetLoader : lingLoader.get();
        hostSupport.cleanupCompatibilityArtifacts(beanNamesToRemove, routesToRemove, mappingInfoMap,
                cleanupLoader, controllerClassesToRemove);
        for (String routeKey : routesToRemove) {
            compatibilityBeanNamesByRoute.remove(routeKey);
        }
        refreshCompatibilityMappings(remainingMap, routesToRefresh, cleanupLoader);

        log.info("Unregistered {} interfaces for ling: {}", removedMetas.size(), lingId);
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

    public WebInterfaceMetadata getMetadata(Object request, HandlerMethod handlerMethod) {
        WebRouteResolution resolution = resolveRoute(request, handlerMethod);
        return resolution != null ? resolution.getMetadata() : null;
    }

    public WebInterfaceMetadata getMetadata(HandlerMethod handlerMethod) {
        return getMetadata(null, handlerMethod);
    }

    @Override
    public WebRouteResolution resolveRoute(Object request) {
        return routeResolver.resolveRoute(request);
    }

    @Override
    public WebRouteResolution resolveRoute(Object request, HandlerMethod handlerMethod) {
        return routeResolver.resolveRoute(request, handlerMethod);
    }

    @Override
    public WebRouteResolution resolveRoute(String routeKey, Object request) {
        return routeResolver.resolveRoute(routeKey, request);
    }

    public LingRuntime resolveRuntime(String lingId) {
        return routeResolver.resolveRuntime(lingId);
    }

    public LingInstance resolveTargetInstance(WebInterfaceMetadata metadata) {
        return routeResolver.resolveTargetInstance(metadata);
    }

    public LingInstance resolveTargetInstance(String lingId, String version) {
        return routeResolver.resolveTargetInstance(lingId, version);
    }

    public Object dispatch(ServletWebRequest webRequest) throws Exception {
        WebRouteResolution resolution = resolveRoute(webRequest);
        return dispatchResolved(resolution, "dynamic gateway", webRequest);
    }

    public Object dispatch(String routeKey, ServletWebRequest webRequest) throws Exception {
        WebRouteResolution resolution = resolveRoute(routeKey, webRequest);
        return dispatchResolved(resolution, routeKey, webRequest);
    }

    private Object dispatchResolved(WebRouteResolution resolution, String routeId, ServletWebRequest webRequest)
            throws Exception {
        WebInterfaceMetadata meta = resolution != null ? resolution.getMetadata() : null;
        if (meta == null) {
            throw new LingException("No available target for route: " + routeId);
        }

        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader targetLoader = meta.getClassLoader();
        if (targetLoader != null) {
            Thread.currentThread().setContextClassLoader(targetLoader);
        }
        try {
            return hostSupport.invokeTarget(meta, routeId, webRequest);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    public LingGatewayHandler gatewayHandler() {
        return new LingGatewayHandler(this);
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
        public Object dispatch(ServletWebRequest webRequest) throws Exception {
            return manager.dispatch(routeKey, webRequest);
        }
    }

    public static class LingGatewayHandler {
        private final WebInterfaceManager manager;

        public LingGatewayHandler(WebInterfaceManager manager) {
            this.manager = manager;
        }

        @ResponseBody
        public Object dispatch(ServletWebRequest webRequest) throws Exception {
            return manager.dispatch(webRequest);
        }
    }

    private String buildRouteKey(WebInterfaceMetadata metadata) {
        return metadata.buildRouteKey();
    }

    private RequestMappingInfo resolveRequestMappingInfo(WebInterfaceMetadata metadata) {
        if (metadata.getRequestMappingInfo() != null) {
            return metadata.getRequestMappingInfo();
        }
        RequestMappingInfo.Builder builder = RequestMappingInfo
                .paths(metadata.getUrlPattern())
                .methods(RequestMethod.valueOf(metadata.getHttpMethod()));
        if (metadata.getParams() != null && metadata.getParams().length > 0) {
            builder.params(metadata.getParams());
        }
        if (metadata.getHeaders() != null && metadata.getHeaders().length > 0) {
            builder.headers(metadata.getHeaders());
        }
        if (metadata.getConsumes() != null && metadata.getConsumes().length > 0) {
            builder.consumes(metadata.getConsumes());
        }
        if (metadata.getProduces() != null && metadata.getProduces().length > 0) {
            builder.produces(metadata.getProduces());
        }
        return builder.build();
    }

    private void rebuildRoutePatternIndex() {
        routePatternsByMethod.clear();
        metadataMap.values().forEach(metas -> {
            if (metas == null) {
                return;
            }
            for (WebInterfaceMetadata meta : metas) {
                if (meta == null || meta.getHttpMethod() == null || meta.getUrlPattern() == null) {
                    continue;
                }
                routePatternsByMethod
                        .computeIfAbsent(meta.getHttpMethod(), key -> ConcurrentHashMap.newKeySet())
                        .add(meta.getUrlPattern());
            }
        });
    }

    private void refreshCompatibilityMappings(Map<String, List<WebInterfaceMetadata>> remainingMap,
                                              Set<String> routesToRefresh,
                                              ClassLoader cleanupLoader) {
        if (routesToRefresh == null || routesToRefresh.isEmpty()) {
            return;
        }
        // 传入有效的 cleanupLoader 以确保 Adapter 缓存被正确清理
        hostSupport.cleanupCompatibilityArtifacts(Collections.emptySet(), routesToRefresh,
                mappingInfoMap, cleanupLoader, Collections.emptySet());
        for (String routeKey : routesToRefresh) {
            List<WebInterfaceMetadata> candidates = remainingMap.get(routeKey);
            WebInterfaceMetadata replacement = selectCompatibilityMetadata(candidates);
            if (replacement == null) {
                compatibilityBeanNamesByRoute.remove(routeKey);
                continue;
            }
            try {
                Method targetMethod = requireTargetMethod(replacement, routeKey);
                RequestMappingInfo mappingInfo = resolveRequestMappingInfo(replacement);
                hostSupport.registerMapping(routeKey, mappingInfo, replacement.getSpringDocBeanName(),
                        targetMethod, mappingInfoMap);
                compatibilityBeanNamesByRoute.put(routeKey, replacement.getSpringDocBeanName());
                log.info("Refreshed SpringDoc compatibility mapping: {} {}",
                        replacement.getHttpMethod(), replacement.getUrlPattern());
            } catch (Exception e) {
                compatibilityBeanNamesByRoute.remove(routeKey);
                log.warn("Failed to refresh SpringDoc compatibility mapping: {}", routeKey, e);
            }
        }
    }

    private WebInterfaceMetadata selectCompatibilityMetadata(List<WebInterfaceMetadata> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        for (WebInterfaceMetadata candidate : candidates) {
            if (candidate != null && candidate.getSpringDocBeanName() != null) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isControllerClassStillRegistered(Class<?> targetClass) {
        if (targetClass == null) {
            return false;
        }
        for (List<WebInterfaceMetadata> metas : metadataMap.values()) {
            if (metas == null) {
                continue;
            }
            for (WebInterfaceMetadata meta : metas) {
                if (meta == null) {
                    continue;
                }
                Class<?> remainingClass = meta.getTargetClass();
                // 只有完全相同的 Class 对象（即由同一个 ClassLoader 加载的同名类）
                // 才说明该 Controller 在本实例的其他路由中还有注册关联
                if (remainingClass == targetClass) {
                    return true;
                }
            }
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
