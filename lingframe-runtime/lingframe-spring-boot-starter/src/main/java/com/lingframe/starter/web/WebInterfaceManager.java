package com.lingframe.starter.web;

import com.lingframe.api.exception.LingException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.TrafficRouter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.CachedIntrospectionResults;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 负责灵元 Controller 的 Web 接口注册、路由解析与调用分发。
 */
@Slf4j
public class WebInterfaceManager implements WebRouteResolver {

    private final Map<String, List<WebInterfaceMetadata>> metadataMap = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> routePatternsByMethod = new ConcurrentHashMap<>();
    private final Map<String, RequestMappingInfo> mappingInfoMap = new ConcurrentHashMap<>();

    // 🔥 注册/注销串行化锁：替代原 executor.submit().get() 模式
    // 旧模式下 submit(...).get() 会阻塞调用线程并占用 executor 单线程槽位，
    // 一旦调用线程持有 Spring 启动锁而 executor 线程又需要该锁，就会形成死锁。
    // 改为同步代码块后，注册/注销在调用线程内串行执行，不再借助 executor 等待。
    private final Object registryLock = new Object();

    private final ExecutorService registryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "LingFrame-WebInterfaceManager");
        thread.setDaemon(true);
        thread.setContextClassLoader(WebInterfaceManager.class.getClassLoader());
        return thread;
    });

    private final DefaultWebRouteResolver routeResolver;
    private final SpringWebCoreSupport coreWebSupport = new SpringWebCoreSupport();

    public static final String REQUEST_METADATA_KEY = "ling.web.metadata";
    public static final String REQUEST_ROUTE_RESOLUTION_KEY = "ling.web.route.resolution";
    public static final String REQUEST_TARGET_VERSION_KEY = "ling.target.version";

    public WebInterfaceManager(LingRepository lingRepository,
            TrafficRouter trafficRouter) {
        this(lingRepository, trafficRouter, Collections.<String>emptyList());
    }

    public WebInterfaceManager(LingRepository lingRepository,
            TrafficRouter trafficRouter,
            List<String> trustedForwardedPrefixes) {
        // C10：转发前缀白名单默认空（不采信客户端转发头），由装配层显式注入可信值
        this.routeResolver = new DefaultWebRouteResolver(
                metadataMap, routePatternsByMethod, lingRepository, trafficRouter, trustedForwardedPrefixes);
    }

    public void init(RequestMappingHandlerMapping mapping,
            RequestMappingHandlerAdapter adapter,
            ConfigurableApplicationContext coreContext) {
        this.coreWebSupport.init(mapping, adapter, coreContext);
        log.info("LingFrame WebInterfaceManager initialized with native registration");
    }

    public void register(WebInterfaceMetadata metadata) {
        registryExecutor.execute(() -> {
            synchronized (registryLock) {
                registerInternal(metadata, false);
            }
        });
    }

    public void registerSync(WebInterfaceMetadata metadata) {
        // 🔥 同步注册：直接在调用线程内执行，避免 executor.submit().get() 死锁
        // registryLock 串行化所有注册/注销操作，保证与异步路径互斥
        synchronized (registryLock) {
            registerInternal(metadata, true);
        }
    }

    public void unregister(String lingId, ClassLoader targetLoader) {
        registryExecutor.execute(() -> {
            synchronized (registryLock) {
                unregisterInternal(lingId, targetLoader);
            }
        });
    }

    public void unregisterSync(String lingId, ClassLoader targetLoader) {
        // 🔥 同步注销：直接在调用线程内执行，避免 executor.submit().get() 死锁
        synchronized (registryLock) {
            unregisterInternal(lingId, targetLoader);
        }
    }

    public Map<String, List<WebInterfaceMetadata>> getMetadataMap() {
        return Collections.unmodifiableMap(metadataMap);
    }

    // 🔥 灵核代理方法缓存（static，只解析一次，由 AppClassLoader 加载）
    private static final Method CORE_DISPATCH_METHOD;
    static {
        try {
            CORE_DISPATCH_METHOD = LingWebEntryHandler.class.getMethod("dispatch", ServletWebRequest.class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError("Cannot find LingWebEntryHandler.dispatch method: " + e);
        }
    }

    private void registerInternal(WebInterfaceMetadata metadata, boolean throwOnError) {
        if (!coreWebSupport.isInitialized()) {
            log.warn("WebInterfaceManager not initialized, skipping registration: {}", metadata.getUrlPattern());
            return;
        }

        String routeKey = buildRouteKey(metadata);

        try {
            // 保留 targetMethod 引用用于内部调用分发，但不再暴露给灵核
            Method targetMethod = requireTargetMethod(metadata, routeKey);
            metadata.minimizeCoreStrongReferences();

            RequestMappingInfo info = resolveRequestMappingInfo(metadata);

            if (!mappingInfoMap.containsKey(routeKey)) {
                // 🔥 架构级断绝：注册灵核加载的 LingWebEntryHandler 代替灵元 Method
                // Spring MVC 只会看到 LingWebEntryHandler.dispatch(ServletWebRequest)
                // 其 HandlerMapping 内部缓存永远只持有 AppClassLoader 的类引用
                LingWebEntryHandler entryHandler = new LingWebEntryHandler(this, routeKey);
                coreWebSupport.registerMapping(routeKey, info, entryHandler, CORE_DISPATCH_METHOD, mappingInfoMap);
                log.info("Registered proxy mapping: {} {}", metadata.getHttpMethod(), metadata.getUrlPattern());
                // ✅ 注册后清理 SpringDoc 缓存，确保 UI 同步
                coreWebSupport.clearSpringDocCache();
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
        if (!coreWebSupport.isInitialized()) {
            return;
        }

        log.info("Unregistering interfaces for ling: {} (ClassLoader: {})",
                lingId, targetLoader != null ? targetLoader.hashCode() : "ALL");

        List<String> routesToRemove = new ArrayList<>();
        List<WebInterfaceMetadata> removedMetas = new ArrayList<>();

        // 原子地逐 routeKey 更新：compute 保证读-改-写对单个 key 原子，
        // dispatch 线程要么看到旧 list 要么看到新 list，不会读到中间态。
        // 取 keySet 快照避免 compute 期间结构性修改触发 ConcurrentModificationException。
        for (String routeKey : new ArrayList<>(metadataMap.keySet())) {
            metadataMap.compute(routeKey, (key, metas) -> {
                if (metas == null || metas.isEmpty()) {
                    return metas;
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
                    return null;
                }
                return remaining;
            });
        }

        rebuildRoutePatternIndex();

        // ✅ 清理灵元自己的 RequestMappingHandlerAdapter
        // 内部缓存（initBinderCache/modelAttributeCache/sessionAttributesHandlerCache）。
        // 这些缓存以灵元 Controller Class 为 key，持有灵元 Method → Class → ClassLoader 强引用。
        // SB3 的 ConfigurableApplicationContext.close() 不再触发 Adapter 缓存清空（SB2 会触发），
        // 不显式清理会阻止灵元 ClassLoader 被 GC 回收。在灵元 context close 之前、meta.clearReferences
        // 之前调用，
        // 因为 clearReferences 会清掉 lingApplicationContextRef，导致 getLingApplicationContext
        // 返回 null。
        // 对 SB2 无副作用：灵元 Class 在灵核 ClassLoader 下查不到对应 key，remove 是 no-op。
        for (WebInterfaceMetadata meta : removedMetas) {
            ApplicationContext lingContext = meta.getLingApplicationContext();
            if (lingContext != null) {
                coreWebSupport.clearLingAdapterCaches(lingContext, targetLoader);
                // 同一灵元 context 多个 meta 共享 Adapter，清理一次即可
                break;
            }
        }

        for (WebInterfaceMetadata meta : removedMetas) {
            meta.clearReferences();
        }

        // 🔥 纯代理架构下，卸载只需移除 HandlerMapping 中的路由映射
        // 无需清理灵核的任何 Bean、BPP 缓存或 Adapter 缓存
        // 因为灵核从未接触过灵元的类
        coreWebSupport.unregisterMappings(routesToRemove, mappingInfoMap, targetLoader);

        // ✅ 注销后立即清理 SpringDoc 缓存，防止 UI 出现过期路由
        coreWebSupport.clearSpringDocCache();

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
        // 指标统一由 LingWebGovernanceFilter.recordWebMetrics 计量（唯一计量点），
        // 本分发路径不再记录 success/failure，避免同一 Web 请求被双层双计（C1）。
        try {
            return coreWebSupport.invokeTarget(meta, routeId, webRequest);
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
        // 先在本地构建目标状态：每个 method 对应其完整的 urlPattern 集合
        Map<String, Set<String>> desired = new HashMap<>();
        metadataMap.values().forEach(metas -> {
            if (metas == null) {
                return;
            }
            for (WebInterfaceMetadata meta : metas) {
                if (meta == null || meta.getHttpMethod() == null || meta.getUrlPattern() == null) {
                    continue;
                }
                desired.computeIfAbsent(meta.getHttpMethod(), k -> new HashSet<>())
                        .add(meta.getUrlPattern());
            }
        });

        // 原子地逐 method 更新：先构建完整 Set 再 put，保证 dispatch 线程
        // 要么读到旧 Set 要么读到新完整 Set，永远不会读到空 Set 导致误判 404。
        // 这是对原 clear()+逐个 add 方案的关键修复：消除 clear 窗口期全路由 404 的竞态。
        for (Map.Entry<String, Set<String>> entry : desired.entrySet()) {
            Set<String> patterns = ConcurrentHashMap.newKeySet();
            patterns.addAll(entry.getValue());
            routePatternsByMethod.put(entry.getKey(), patterns);
        }
        // 清理不再有路由的 method。此时 desired 中的方法已全部 put 完毕，
        // retainAll 只会移除已无路由的 method，不影响仍有效的 method。
        routePatternsByMethod.keySet().retainAll(desired.keySet());
    }

    private Method requireTargetMethod(WebInterfaceMetadata metadata, String routeKey) {
        Method targetMethod = metadata.getTargetMethod();
        if (targetMethod == null) {
            throw new IllegalStateException("Target method no longer available for route: " + routeKey);
        }
        return targetMethod;
    }
}
