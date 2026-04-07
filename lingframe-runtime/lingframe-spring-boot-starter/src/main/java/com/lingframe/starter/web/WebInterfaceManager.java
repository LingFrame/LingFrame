package com.lingframe.starter.web;

import com.lingframe.api.exception.LingException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.metrics.LingHealthMetrics;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.spi.TrafficRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.CachedIntrospectionResults;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 负责灵元 Controller 的 Web 接口注册、路由解析与调用分发。
 */
@Slf4j
public class WebInterfaceManager implements WebRouteResolver {

    private final Map<String, List<WebInterfaceMetadata>> metadataMap = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> routePatternsByMethod = new ConcurrentHashMap<>();
    private final Map<String, RequestMappingInfo> mappingInfoMap = new ConcurrentHashMap<>();

    private final ExecutorService registryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "LingFrame-WebInterfaceManager");
        thread.setDaemon(true);
        thread.setContextClassLoader(WebInterfaceManager.class.getClassLoader());
        return thread;
    });

    private final DefaultWebRouteResolver routeResolver;
    private final SpringWebHostSupport hostSupport = new SpringWebHostSupport();
    private final ObjectProvider<MetricsCollector> metricsCollectorProvider;

    public static final String REQUEST_METADATA_KEY = "ling.web.metadata";
    public static final String REQUEST_ROUTE_RESOLUTION_KEY = "ling.web.route.resolution";
    public static final String REQUEST_TARGET_VERSION_KEY = "ling.target.version";

    public WebInterfaceManager(LingRepository lingRepository,
            TrafficRouter trafficRouter,
            ObjectProvider<MetricsCollector> metricsCollectorProvider) {
        this.routeResolver = new DefaultWebRouteResolver(
                metadataMap, routePatternsByMethod, lingRepository, trafficRouter);
        this.metricsCollectorProvider = metricsCollectorProvider;
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

    public Map<String, List<WebInterfaceMetadata>> getMetadataMap() {
        return Collections.unmodifiableMap(metadataMap);
    }

    // 🔥 宿主代理方法缓存（static，只解析一次，由 AppClassLoader 加载）
    private static final Method HOST_DISPATCH_METHOD;
    static {
        try {
            HOST_DISPATCH_METHOD = LingWebEntryHandler.class.getMethod("dispatch", ServletWebRequest.class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError("Cannot find LingWebEntryHandler.dispatch method: " + e);
        }
    }

    private void registerInternal(WebInterfaceMetadata metadata, boolean throwOnError) {
        if (!hostSupport.isInitialized()) {
            log.warn("WebInterfaceManager not initialized, skipping registration: {}", metadata.getUrlPattern());
            return;
        }

        String routeKey = buildRouteKey(metadata);

        try {
            // 保留 targetMethod 引用用于内部调用分发，但不再暴露给宿主
            Method targetMethod = requireTargetMethod(metadata, routeKey);
            metadata.minimizeHostReferences();

            RequestMappingInfo info = resolveRequestMappingInfo(metadata);

            if (!mappingInfoMap.containsKey(routeKey)) {
                // 🔥 架构级断绝：注册宿主加载的 LingWebEntryHandler 代替灵元 Method
                // Spring MVC 只会看到 LingWebEntryHandler.dispatch(ServletWebRequest)
                // 其 HandlerMapping 内部缓存永远只持有 AppClassLoader 的类引用
                LingWebEntryHandler entryHandler = new LingWebEntryHandler(this, routeKey);
                hostSupport.registerMapping(routeKey, info, entryHandler, HOST_DISPATCH_METHOD, mappingInfoMap);
                log.info("Registered proxy mapping: {} {}", metadata.getHttpMethod(), metadata.getUrlPattern());
                // ✅ 注册后清理 SpringDoc 缓存，确保 UI 同步
                hostSupport.clearSpringDocCache();
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
        Map<String, List<WebInterfaceMetadata>> remainingMap = new HashMap<>();
        List<WebInterfaceMetadata> removedMetas = new ArrayList<>();

        metadataMap.forEach((routeKey, metas) -> {
            if (metas == null || metas.isEmpty()) {
                return;
            }
            List<WebInterfaceMetadata> remaining = new ArrayList<>();
            for (WebInterfaceMetadata meta : metas) {
                ClassLoader metaLoader = meta.getClassLoader();
                boolean loaderMatches = targetLoader == null
                        || metaLoader == targetLoader
                        || metaLoader == null;

                if (meta.getLingId().equals(lingId) && loaderMatches) {
                    removedMetas.add(meta);
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

        // 🔥 纯代理架构下，卸载只需移除 HandlerMapping 中的路由映射
        // 无需清理宿主的任何 Bean、BPP 缓存或 Adapter 缓存
        // 因为宿主从未接触过灵元的类
        hostSupport.unregisterMappings(routesToRemove, mappingInfoMap);
        
        // ✅ 注销后立即清理 SpringDoc 缓存，防止 UI 出现过期路由
        hostSupport.clearSpringDocCache();

        // 🔥 彻底解决“无法卸载”的关键：强制清理 Spring 核心缓存池对该 ClassLoader 的引用
        if (targetLoader != null) {
            CachedIntrospectionResults.clearClassLoader(targetLoader);
        }

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
        long startNanos = System.nanoTime();
        try {
            Object result = hostSupport.invokeTarget(meta, routeId, webRequest);
            recordMetrics(meta, resolution, startNanos, true, null);
            return result;
        } catch (Exception ex) {
            recordMetrics(meta, resolution, startNanos, false, ex);
            throw ex;
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private void recordMetrics(WebInterfaceMetadata meta,
            WebRouteResolution resolution,
            long startNanos,
            boolean success,
            Throwable error) {
        MetricsCollector metricsCollector = metricsCollectorProvider != null ? metricsCollectorProvider.getIfAvailable() : null;
        if (metricsCollector == null || meta == null || meta.getLingId() == null || meta.getLingId().isEmpty()) {
            return;
        }

        long costMs = (System.nanoTime() - startNanos) / 1_000_000;
        String lingId = meta.getLingId();
        String version = resolution != null && resolution.getTargetInstance() != null
                ? resolution.getTargetInstance().getVersion()
                : meta.getVersion();

        LingHealthMetrics metrics = metricsCollector.getOrCreate(lingId);
        LingHealthMetrics versionMetrics = metricsCollector.getOrCreate(lingId, version);
        if (success) {
            metrics.recordSuccess(costMs);
            if (versionMetrics != metrics) {
                versionMetrics.recordSuccess(costMs);
            }
            return;
        }

        boolean isTimeout = isTimeoutError(error);
        metrics.recordFailure(costMs, isTimeout);
        if (versionMetrics != metrics) {
            versionMetrics.recordFailure(costMs, isTimeout);
        }
    }

    private boolean isTimeoutError(Throwable error) {
        if (error == null) {
            return false;
        }
        String message = error.getMessage();
        if (message != null) {
            String lower = message.toLowerCase();
            if (lower.contains("timeout") || lower.contains("timed out")) {
                return true;
            }
        }
        return isTimeoutError(error.getCause());
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

    private Method requireTargetMethod(WebInterfaceMetadata metadata, String routeKey) {
        Method targetMethod = metadata.getTargetMethod();
        if (targetMethod == null) {
            throw new IllegalStateException("Target method no longer available for route: " + routeKey);
        }
        return targetMethod;
    }
}
