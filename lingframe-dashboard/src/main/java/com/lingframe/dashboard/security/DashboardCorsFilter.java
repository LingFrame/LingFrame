package com.lingframe.dashboard.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;

/**
 * Dashboard 集中式 CORS + CSRF-Origin 过滤器
 * <p>
 * 替代各 Controller 上分散的 {@code @CrossOrigin(origins = "*")}，
 * 提供统一的、可配置的跨域管控入口。当 access-token 认证开启且
 * 未配置 allowed-origins 时，仅允许同源请求（生产安全默认值）。
 * <p>
 * 对状态变更请求（POST / DELETE / PUT / PATCH），即使非预检请求
 * 也会校验 {@code Origin} 头作为 CSRF 防护。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class DashboardCorsFilter implements Filter {

    private final CorsProperties corsProperties;
    private final AccessTokenProperties accessTokenProperties;

    public DashboardCorsFilter(CorsProperties corsProperties,
                               AccessTokenProperties accessTokenProperties) {
        this.corsProperties = corsProperties;
        this.accessTokenProperties = accessTokenProperties;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI();

        // 仅拦截 Dashboard API 端点
        if (!path.startsWith("/lingframe/dashboard/")) {
            chain.doFilter(request, response);
            return;
        }

        if (!corsProperties.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String origin = request.getHeader("Origin");

        // --- 预检请求 (OPTIONS) ---
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            // 无条件设置 Vary: Origin，确保代理缓存正确性
            response.setHeader("Vary", "Origin");
            if (origin != null && isOriginAllowed(origin, request)) {
                setCorsHeaders(response, origin);
            }
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        // --- 实际请求 ---
        if (origin != null) {
            // 跨域请求：校验 origin 是否在允许列表中
            if (!isOriginAllowed(origin, request)) {
                log.warn("CORS rejected: origin={}, path={}, method={}", origin, path, request.getMethod());
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"Origin not allowed\"}");
                return;
            }
            setCorsHeaders(response, origin);
        }

        // --- CSRF 防护：对状态变更请求校验 Origin 头 ---
        String method = request.getMethod().toUpperCase();
        if (isStateChangingMethod(method) && accessTokenProperties.isEnabled()) {
            if (origin == null) {
                // 认证开启时，状态变更请求缺少 Origin 头直接拒绝
                log.warn("CSRF rejected: missing Origin header, path={}, method={}", path, method);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"Missing Origin header\"}");
                return;
            }
            // Origin 已在上方 isOriginAllowed 中校验通过
        }

        chain.doFilter(request, response);
    }

    private boolean isOriginAllowed(String origin, HttpServletRequest request) {
        List<String> allowed = corsProperties.getAllowedOrigins();

        // 已配置显式源列表时，逐一比对（大小写不敏感）
        if (!allowed.isEmpty()) {
            return allowed.stream().anyMatch(o -> o.equalsIgnoreCase(origin));
        }

        // 判断是否为同源请求：通过 URI 解析比对 scheme + host + port 三元组。
        // 不能用字符串前缀匹配（origin + "/" startsWith）：
        //   1) scheme/host 大小写不一致会导致误判；
        //   2) 浏览器对默认端口（http=80、https=443）会省略端口，
        //      但 getRequestURL() 可能显式带端口，导致 http://host 与 http://host:80 不匹配；
        //   3) URI 解析能统一处理上述边界，更安全可靠。
        if (isSameOrigin(origin, request)) {
            return true;
        }

        // 未配置显式源列表且非同源：
        // access-token 开启 → 拒绝跨域
        if (accessTokenProperties.isEnabled()) {
            return false;
        }

        // 开发模式：access-token 未启用，无源配置 = 宽松放行
        return true;
    }

    /**
     * 比对 Origin 头与当前请求是否同源（scheme + host + port 三元组完全一致）。
     * <p>
     * 处理默认端口隐含问题：http 协议默认 80、https 协议默认 443，
     * 浏览器对默认端口的 Origin 会省略端口，而服务器侧 URL 可能显式带端口，
     * 因此 -1（未指定）与默认端口号视为等价。
     *
     * @param origin  浏览器发送的 Origin 头值（scheme://host[:port]，无尾随斜杠）
     * @param request 当前 HTTP 请求
     * @return 同源返回 true；Origin 非法或异源返回 false
     */
    private boolean isSameOrigin(String origin, HttpServletRequest request) {
        if (origin == null || origin.isEmpty()) {
            return false;
        }
        URI originUri;
        URI requestUri;
        try {
            originUri = new URI(origin);
            requestUri = new URI(request.getRequestURL().toString());
        } catch (URISyntaxException e) {
            // Origin 头或请求 URL 格式非法，按异源处理（保守拒绝）
            log.debug("Cannot parse origin or request URL for same-origin check: origin={}", origin, e);
            return false;
        }
        // scheme 大小写不敏感比对
        if (!Objects.equals(lower(originUri.getScheme()), lower(requestUri.getScheme()))) {
            return false;
        }
        // host 大小写不敏感比对
        String originHost = originUri.getHost();
        String requestHost = requestUri.getHost();
        if (originHost == null || requestHost == null
                || !originHost.equalsIgnoreCase(requestHost)) {
            return false;
        }
        // 端口比对，处理默认端口隐含（-1 与协议默认端口等价）
        return equivalentPort(originUri.getPort(), requestUri.getPort(),
                lower(requestUri.getScheme()));
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase();
    }

    /**
     * 判断两个端口在给定 scheme 下是否等价：直接相等，或一方为 -1（未指定）且另一方为该 scheme 的默认端口。
     */
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
