package com.lingframe.dashboard.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;

/**
 * Dashboard CORS + CSRF-Origin 过滤器。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class DashboardCorsFilter implements Filter {

    private final CorsProperties corsProperties;
    private final AccessTokenProperties accessTokenProperties;

    public DashboardCorsFilter(CorsProperties corsProperties, AccessTokenProperties accessTokenProperties) {
        this.corsProperties = corsProperties;
        this.accessTokenProperties = accessTokenProperties;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI();

        if (!path.startsWith("/lingframe/dashboard/")) {
            chain.doFilter(request, response);
            return;
        }
        if (!corsProperties.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String origin = request.getHeader("Origin");

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setHeader("Vary", "Origin");
            if (origin != null && isOriginAllowed(origin, request)) {
                setCorsHeaders(response, origin);
            }
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        if (origin != null) {
            if (!isOriginAllowed(origin, request)) {
                log.warn("CORS rejected: origin={}, path={}, method={}", origin, path, request.getMethod());
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"Origin not allowed\"}");
                return;
            }
            setCorsHeaders(response, origin);
        }

        String method = request.getMethod().toUpperCase();
        if (isStateChangingMethod(method) && accessTokenProperties.isEnabled()) {
            if (origin == null) {
                log.warn("CSRF rejected: missing Origin header, path={}, method={}", path, method);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"Missing Origin header\"}");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean isOriginAllowed(String origin, HttpServletRequest request) {
        List<String> allowed = corsProperties.getAllowedOrigins();
        if (!allowed.isEmpty()) {
            return allowed.stream().anyMatch(o -> o.equalsIgnoreCase(origin));
        }
        if (isSameOrigin(origin, request)) {
            return true;
        }
        return !accessTokenProperties.isEnabled();
    }

    private boolean isSameOrigin(String origin, HttpServletRequest request) {
        if (origin == null || origin.isEmpty()) {
            return false;
        }
        try {
            URI originUri = new URI(origin);
            URI requestUri = new URI(request.getRequestURL().toString());
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

    private void setCorsHeaders(HttpServletResponse response, String origin) {
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Access-Control-Allow-Methods", String.join(", ", corsProperties.getAllowedMethods()));
        response.setHeader("Access-Control-Allow-Headers", String.join(", ", corsProperties.getAllowedHeaders()));
        response.setHeader("Access-Control-Max-Age", String.valueOf(corsProperties.getMaxAge()));
        response.setHeader("Vary", "Origin");
    }

    private static boolean isStateChangingMethod(String method) {
        return "POST".equals(method) || "DELETE".equals(method)
                || "PUT".equals(method) || "PATCH".equals(method);
    }
}
