package com.lingframe.dashboard.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Dashboard CORS + CSRF-Origin 决策器。
 * <p>
 * 主干逻辑，与 Servlet 命名空间无关；origin 校验、同源判断、端口等价均为纯 Java，
 * 不再随 javax/jakarta 双份复制。分支 {@code DashboardCorsFilter} 薄壳委托本类。
 *
 * @author lingframe
 */
@Slf4j
@RequiredArgsConstructor
public class CorsPolicy {

    private final CorsProperties corsProperties;
    private final AccessTokenProperties accessTokenProperties;

    /**
     * 对请求做 CORS / CSRF 决策。
     *
     * @param snapshot 请求快照
     * @return 放行（可能携带 CORS 头）或终止决策
     */
    public SecurityDecision check(RequestSnapshot snapshot) {
        String path = snapshot.getRequestUri();
        if (!path.startsWith("/lingframe/dashboard/")) {
            return SecurityDecision.proceed();
        }
        if (!corsProperties.isEnabled()) {
            return SecurityDecision.proceed();
        }

        String origin = snapshot.getHeader("Origin");
        String method = snapshot.getMethod();

        if ("OPTIONS".equalsIgnoreCase(method)) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Vary", "Origin");
            if (origin != null && isOriginAllowed(origin, snapshot)) {
                headers.putAll(corsHeaders(origin));
            }
            return SecurityDecision.terminateWithHeaders(200, headers);
        }

        if (origin != null) {
            if (!isOriginAllowed(origin, snapshot)) {
                log.warn("CORS rejected: origin={}, path={}, method={}", origin, path, method);
                return SecurityDecision.terminate(403, "application/json;charset=UTF-8",
                        "{\"success\":false,\"message\":\"Origin not allowed\"}");
            }
            // origin 受信：设置 CORS 头后放行（origin != null 时 CSRF 校验不会触发）
            return SecurityDecision.proceedWithHeaders(corsHeaders(origin));
        }

        // origin == null：状态变更方法需 CSRF 校验
        String upperMethod = method.toUpperCase();
        if (isStateChangingMethod(upperMethod) && accessTokenProperties.isEnabled()) {
            log.warn("CSRF rejected: missing Origin header, path={}, method={}", path, upperMethod);
            return SecurityDecision.terminate(403, "application/json;charset=UTF-8",
                    "{\"success\":false,\"message\":\"Missing Origin header\"}");
        }
        return SecurityDecision.proceed();
    }

    private boolean isOriginAllowed(String origin, RequestSnapshot snapshot) {
        List<String> allowed = corsProperties.getAllowedOrigins();
        if (!allowed.isEmpty()) {
            return allowed.stream().anyMatch(o -> o.equalsIgnoreCase(origin));
        }
        if (isSameOrigin(origin, snapshot)) {
            return true;
        }
        return !accessTokenProperties.isEnabled();
    }

    private boolean isSameOrigin(String origin, RequestSnapshot snapshot) {
        if (origin == null || origin.isEmpty()) {
            return false;
        }
        try {
            URI originUri = new URI(origin);
            URI requestUri = new URI(snapshot.getRequestUrl());
            if (!Objects.equals(lower(originUri.getScheme()), lower(requestUri.getScheme()))) {
                return false;
            }
            String originHost = originUri.getHost();
            String requestHost = requestUri.getHost();
            if (originHost == null || requestHost == null || !originHost.equalsIgnoreCase(requestHost)) {
                return false;
            }
            return equivalentPort(originUri.getPort(), requestUri.getPort(), lower(requestUri.getScheme()));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase();
    }

    private static boolean equivalentPort(int p1, int p2, String schemeLower) {
        if (p1 == p2) {
            return true;
        }
        int defaultPort = "https".equals(schemeLower) ? 443 : 80;
        return (p1 == -1 && p2 == defaultPort) || (p2 == -1 && p1 == defaultPort);
    }

    private Map<String, String> corsHeaders(String origin) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Access-Control-Allow-Origin", origin);
        headers.put("Access-Control-Allow-Methods", String.join(", ", corsProperties.getAllowedMethods()));
        headers.put("Access-Control-Allow-Headers", String.join(", ", corsProperties.getAllowedHeaders()));
        headers.put("Access-Control-Max-Age", String.valueOf(corsProperties.getMaxAge()));
        headers.put("Vary", "Origin");
        return headers;
    }

    private static boolean isStateChangingMethod(String method) {
        return "POST".equals(method) || "DELETE".equals(method)
                || "PUT".equals(method) || "PATCH".equals(method);
    }
}
