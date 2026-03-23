package com.lingframe.starter.web;

import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.spi.TrafficRouter;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 {@link WebInterfaceMetadata} 的默认 Web 路由解析器。
 */
public final class DefaultWebRouteResolver implements WebRouteResolver {

    private final Map<String, List<WebInterfaceMetadata>> metadataMap;
    private final Map<String, Set<String>> routePatternsByMethod;
    private final LingRepository lingRepository;
    private final TrafficRouter trafficRouter;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public DefaultWebRouteResolver(Map<String, List<WebInterfaceMetadata>> metadataMap,
                                   Map<String, Set<String>> routePatternsByMethod,
                                   LingRepository lingRepository,
                                   TrafficRouter trafficRouter) {
        this.metadataMap = metadataMap;
        this.routePatternsByMethod = routePatternsByMethod;
        this.lingRepository = lingRepository;
        this.trafficRouter = trafficRouter;
    }

    @Override
    public WebRouteResolution resolveRoute(Object request) {
        WebRouteResolution cached = readCachedResolution(null, request);
        if (cached != null) {
            return cached;
        }

        String httpMethod = resolveHttpMethod(request);
        String lookupPath = WebRequestPathSupport.resolveLookupPath(request);
        if (httpMethod == null || lookupPath == null) {
            return null;
        }

        if (!hasRouteCandidates(httpMethod)) {
            return null;
        }

        List<String> routeKeys = findMatchingRouteKeys(httpMethod, lookupPath, request);
        if (routeKeys.isEmpty()) {
            return null;
        }

        for (String routeKey : routeKeys) {
            WebRouteResolution resolution = resolveRoute(routeKey, request);
            if (resolution != null) {
                return resolution;
            }
        }
        return null;
    }

    @Override
    public WebRouteResolution resolveRoute(Object request, HandlerMethod handlerMethod) {
        WebRouteResolution cached = readCachedResolution(null, request);
        if (cached != null) {
            return cached;
        }
        if (handlerMethod == null) {
            return null;
        }

        Object bean = handlerMethod.getBean();
        if (bean instanceof WebInterfaceManager.LingWebEntryHandler) {
            return resolveRoute(((WebInterfaceManager.LingWebEntryHandler) bean).getRouteKey(), request);
        }

        Method method = handlerMethod.getMethod();
        for (List<WebInterfaceMetadata> metas : metadataMap.values()) {
            if (metas == null || metas.isEmpty()) {
                continue;
            }
            for (WebInterfaceMetadata meta : metas) {
                if (isSameHandler(meta, bean, method) && meta.matchesRequest(request)) {
                    String routeKey = buildRouteKey(meta);
                    LingRuntime runtime = resolveRuntime(meta.getLingId());
                    return cacheResolution(request, buildResolution(routeKey, meta, runtime, null));
                }
            }
        }
        return null;
    }

    @Override
    public WebRouteResolution resolveRoute(String routeKey, Object request) {
        if (routeKey == null) {
            return null;
        }

        WebRouteResolution cached = readCachedResolution(routeKey, request);
        if (cached != null) {
            return cached;
        }

        List<WebInterfaceMetadata> metas = metadataMap.get(routeKey);
        if (metas == null || metas.isEmpty()) {
            return null;
        }

        WebInterfaceMetadata sample = metas.get(0);
        if (!sample.matchesRequest(request)) {
            return null;
        }
        LingRuntime runtime = resolveRuntime(sample.getLingId());

        if (metas.size() == 1) {
            return cacheResolution(request, buildResolution(routeKey, sample, runtime, null));
        }

        String forcedVersion = readRequestAttribute(request, WebInterfaceManager.REQUEST_TARGET_VERSION_KEY, String.class);
        if (forcedVersion != null) {
            WebInterfaceMetadata forcedMeta = resolveByVersion(routeKey, forcedVersion);
            if (forcedMeta != null) {
                return cacheResolution(request, buildResolution(routeKey, forcedMeta, runtime, null));
            }
        }

        List<LingInstance> readyCandidates = resolveReadyCandidates(routeKey, runtime);
        LingInstance routed = route(sample.getLingId(), runtime, readyCandidates);
        if (routed != null) {
            WebInterfaceMetadata matched = resolveByVersion(routeKey, routed.getVersion());
            if (matched != null) {
                return cacheResolution(request, buildResolution(routeKey, matched, runtime, routed));
            }
        }

        WebRouteResolution readyFallback = buildReadyFallbackResolution(routeKey, runtime, readyCandidates);
        if (readyFallback != null) {
            return cacheResolution(request, readyFallback);
        }

        return cacheResolution(request, buildResolution(routeKey, sample, runtime, null));
    }

    public LingRuntime resolveRuntime(String lingId) {
        if (lingRepository == null || lingId == null || lingId.isEmpty()) {
            return null;
        }
        return lingRepository.getRuntime(lingId);
    }

    public LingInstance resolveTargetInstance(WebInterfaceMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        return resolveTargetInstance(metadata.getLingId(), metadata.getVersion());
    }

    public LingInstance resolveTargetInstance(String lingId, String version) {
        if (lingId == null || lingId.isEmpty() || version == null || version.isEmpty()) {
            return null;
        }
        LingRuntime runtime = resolveRuntime(lingId);
        if (runtime == null) {
            return null;
        }
        return resolveTargetInstance(version, runtime);
    }

    private WebRouteResolution buildResolution(String routeKey,
                                               WebInterfaceMetadata metadata,
                                               LingRuntime runtime,
                                               LingInstance preResolvedInstance) {
        WebInterfaceMetadata requestMetadata = metadata != null ? metadata.snapshotForRequest() : null;
        LingInstance targetInstance = preResolvedInstance;
        if (targetInstance == null && requestMetadata != null) {
            targetInstance = resolveTargetInstance(requestMetadata.getVersion(), runtime);
        }
        return new WebRouteResolution(routeKey, requestMetadata, runtime, targetInstance);
    }

    private LingInstance route(String lingId, LingRuntime runtime, List<LingInstance> candidates) {
        if (runtime == null || trafficRouter == null || lingId == null || lingId.isEmpty()) {
            return null;
        }
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        InvocationContext ctx = InvocationContext.obtain();
        try {
            ctx.setTargetLingId(lingId);
            ctx.setServiceFQSID(lingId + ":http");
            ctx.setRuntime(runtime);
            return trafficRouter.route(candidates, ctx);
        } finally {
            ctx.recycle();
        }
    }

    private List<LingInstance> resolveReadyCandidates(String routeKey, LingRuntime runtime) {
        if (runtime == null) {
            return Collections.emptyList();
        }
        List<LingInstance> readyInstances = runtime.getReadyInstances();
        if (readyInstances == null || readyInstances.isEmpty()) {
            return Collections.emptyList();
        }

        List<LingInstance> filtered = new ArrayList<>();
        for (LingInstance instance : readyInstances) {
            if (instance == null || instance.getVersion() == null) {
                continue;
            }
            if (resolveByVersion(routeKey, instance.getVersion()) != null) {
                filtered.add(instance);
            }
        }
        return filtered.isEmpty() ? Collections.emptyList() : filtered;
    }

    private WebRouteResolution buildReadyFallbackResolution(String routeKey,
                                                            LingRuntime runtime,
                                                            List<LingInstance> readyCandidates) {
        if (readyCandidates == null || readyCandidates.isEmpty()) {
            return null;
        }
        for (LingInstance candidate : readyCandidates) {
            WebInterfaceMetadata matched = resolveByVersion(routeKey, candidate.getVersion());
            if (matched != null) {
                return buildResolution(routeKey, matched, runtime, candidate);
            }
        }
        return null;
    }

    private LingInstance resolveTargetInstance(String version, LingRuntime runtime) {
        if (runtime == null || version == null || version.isEmpty()) {
            return null;
        }
        List<LingInstance> readyInstances = runtime.getReadyInstances();
        if (readyInstances == null || readyInstances.isEmpty()) {
            return null;
        }
        for (LingInstance instance : readyInstances) {
            if (instance != null && version.equals(instance.getVersion())) {
                return instance;
            }
        }
        return null;
    }

    private List<String> findMatchingRouteKeys(String httpMethod, String lookupPath, Object request) {
        List<RouteCandidate> candidates = new ArrayList<>();
        List<String> candidateMethods = resolveCandidateHttpMethods(httpMethod);
        for (Map.Entry<String, List<WebInterfaceMetadata>> entry : metadataMap.entrySet()) {
            List<WebInterfaceMetadata> metas = entry.getValue();
            if (metas == null || metas.isEmpty()) {
                continue;
            }

            WebInterfaceMetadata sample = metas.get(0);
            if (sample.getHttpMethod() == null || !candidateMethods.contains(sample.getHttpMethod())) {
                continue;
            }
            if (sample.getUrlPattern() == null || !pathMatcher.match(sample.getUrlPattern(), lookupPath)) {
                continue;
            }
            if (!sample.matchesRequest(request)) {
                continue;
            }
            candidates.add(new RouteCandidate(entry.getKey(), sample));
        }

        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        candidates.sort((left, right) -> compareRouteCandidates(left, right, lookupPath, request));
        List<String> routeKeys = new ArrayList<>(candidates.size());
        for (RouteCandidate candidate : candidates) {
            routeKeys.add(candidate.routeKey);
        }
        return routeKeys;
    }

    private int compareRouteCandidates(RouteCandidate left, RouteCandidate right, String lookupPath, Object request) {
        int byRequestSpecificity = left.metadata.compareRequestSpecificity(right.metadata, request);
        if (byRequestSpecificity != 0) {
            return byRequestSpecificity;
        }

        Comparator<String> comparator = pathMatcher.getPatternComparator(lookupPath);
        int byPattern = comparator.compare(left.metadata.getUrlPattern(), right.metadata.getUrlPattern());
        if (byPattern != 0) {
            return byPattern;
        }
        return left.routeKey.compareTo(right.routeKey);
    }

    private boolean hasRouteCandidates(String httpMethod) {
        for (String candidateMethod : resolveCandidateHttpMethods(httpMethod)) {
            Set<String> patterns = routePatternsByMethod.get(candidateMethod);
            if (patterns != null && !patterns.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private List<String> resolveCandidateHttpMethods(String httpMethod) {
        if ("HEAD".equals(httpMethod)) {
            List<String> methods = new ArrayList<>(2);
            methods.add("HEAD");
            methods.add("GET");
            return methods;
        }
        return Collections.singletonList(httpMethod);
    }

    private WebInterfaceMetadata resolveByVersion(String routeKey, String version) {
        if (routeKey == null || version == null) {
            return null;
        }
        List<WebInterfaceMetadata> metas = metadataMap.get(routeKey);
        if (metas == null || metas.isEmpty()) {
            return null;
        }
        for (WebInterfaceMetadata meta : metas) {
            if (version.equals(meta.getVersion())) {
                return meta;
            }
        }
        return null;
    }

    private WebRouteResolution cacheResolution(Object request, WebRouteResolution resolution) {
        if (request == null || resolution == null) {
            return resolution;
        }
        writeRequestAttribute(request, WebInterfaceManager.REQUEST_ROUTE_RESOLUTION_KEY, resolution);
        WebInterfaceMetadata metadata = resolution.getMetadata();
        if (metadata != null) {
            writeRequestAttribute(request, WebInterfaceManager.REQUEST_METADATA_KEY, metadata);
            if (metadata.getVersion() != null) {
                writeRequestAttribute(request, WebInterfaceManager.REQUEST_TARGET_VERSION_KEY, metadata.getVersion());
            }
        }
        return resolution;
    }

    private WebRouteResolution readCachedResolution(String routeKey, Object request) {
        Object cached = readRequestAttribute(request, WebInterfaceManager.REQUEST_ROUTE_RESOLUTION_KEY);
        if (!(cached instanceof WebRouteResolution)) {
            return null;
        }
        WebRouteResolution resolution = (WebRouteResolution) cached;
        if (routeKey == null || resolution.matchesRouteKey(routeKey)) {
            return resolution;
        }
        return null;
    }

    private boolean isSameHandler(WebInterfaceMetadata meta, Object bean, Method method) {
        return meta.matchesHandler(bean, method);
    }

    private String buildRouteKey(WebInterfaceMetadata metadata) {
        return metadata.buildRouteKey();
    }

    private String resolveHttpMethod(Object request) {
        Object method = invokeNoArgMethod(request, "getMethod");
        return method instanceof String ? (String) method : null;
    }

    private Object invokeNoArgMethod(Object target, String methodName) {
        if (target == null || methodName == null) {
            return null;
        }
        Method method = ReflectionUtils.findMethod(target.getClass(), methodName);
        if (method == null) {
            return null;
        }
        ReflectionUtils.makeAccessible(method);
        return ReflectionUtils.invokeMethod(method, target);
    }

    private Object readRequestAttribute(Object request, String name) {
        return readRequestAttribute(request, name, Object.class);
    }

    @SuppressWarnings("unchecked")
    private <T> T readRequestAttribute(Object request, String name, Class<T> type) {
        if (request == null || name == null) {
            return null;
        }
        Object value = null;
        if (request instanceof RequestAttributes) {
            value = ((RequestAttributes) request).getAttribute(name, RequestAttributes.SCOPE_REQUEST);
        } else {
            Method method = ReflectionUtils.findMethod(request.getClass(), "getAttribute", String.class);
            if (method != null) {
                ReflectionUtils.makeAccessible(method);
                value = ReflectionUtils.invokeMethod(method, request, name);
            }
        }
        if (value == null || !type.isInstance(value)) {
            return null;
        }
        return (T) value;
    }

    private void writeRequestAttribute(Object request, String name, Object value) {
        if (request == null || name == null) {
            return;
        }
        if (request instanceof RequestAttributes) {
            ((RequestAttributes) request).setAttribute(name, value, RequestAttributes.SCOPE_REQUEST);
            return;
        }
        Method method = ReflectionUtils.findMethod(request.getClass(), "setAttribute", String.class, Object.class);
        if (method != null) {
            ReflectionUtils.makeAccessible(method);
            ReflectionUtils.invokeMethod(method, request, name, value);
        }
    }

    private static final class RouteCandidate {
        private final String routeKey;
        private final WebInterfaceMetadata metadata;

        private RouteCandidate(String routeKey, WebInterfaceMetadata metadata) {
            this.routeKey = routeKey;
            this.metadata = metadata;
        }
    }
}
