package com.lingframe.starter.web;

import org.springframework.util.AntPathMatcher;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 供路由解析器与网关映射复用的请求路径辅助工具。
 */
public final class WebRequestPathSupport {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private WebRequestPathSupport() {
    }

    /**
     * 无白名单版本：不采信任何客户端转发头（安全默认，C10）。
     * <p>
     * 需要反代前缀剥离的应用必须显式配置 {@code lingframe.trusted-forwarded-prefixes}，
     * 并调用带白名单的重载。
     */
    public static String resolveLookupPath(Object request) {
        return resolveLookupPath(request, Collections.<String>emptyList());
    }

    /**
     * 解析路由查找路径（C10 安全收敛）。
     * <p>
     * 1. 归一化路径：解码 {@code %2e} 变体并折叠 {@code .} / {@code ..} 段，防止
     *    路由绕过（如 {@code /api/../admin}、{@code /api/%2e%2e/admin} 归一化为 {@code /admin}）。
     * 2. 转发头剥离仅在白名单配置非空时生效：转发头声明的每个前缀都必须与
     *    {@code trustedForwardedPrefixes} 中的值完全匹配才被采信，杜绝伪造
     *    {@code X-Forwarded-Prefix} / {@code X-Forwarded-Path} 头劫持路由。
     *
     * @param trustedForwardedPrefixes 可信任的转发前缀白名单；空则不采信客户端头
     */
    public static String resolveLookupPath(Object request, List<String> trustedForwardedPrefixes) {
        Object uri = invokeNoArgMethod(request, "getRequestURI");
        if (!(uri instanceof String)) {
            return null;
        }

        String requestUri = normalizePath((String) uri);
        requestUri = stripPrefixes(requestUri, resolveForwardedPrefixes(request, trustedForwardedPrefixes));

        String contextPath = readString(invokeNoArgMethod(request, "getContextPath"));
        requestUri = stripPrefix(requestUri, contextPath);

        String servletPath = readString(invokeNoArgMethod(request, "getServletPath"));
        // 默认 servlet（映射 "/"，Spring Boot 常见形态）下 getServletPath() 返回的
        // 是整个请求路径而非空：此时若照单全收会把它整体剥掉，lookupPath 退化为 "/"，
        // 导致灵元路由全 404。仅在 servletPath 是短于请求路径的真实前缀段时才剥离。
        if (servletPath != null && !servletPath.isEmpty() && !servletPath.equals(requestUri)
                && requestUri.startsWith(servletPath)) {
            requestUri = stripPrefix(requestUri, servletPath);
        }

        return requestUri.isEmpty() ? "/" : requestUri;
    }

    public static Map<String, String> extractUriVariables(String pattern, String lookupPath) {
        if (pattern == null || lookupPath == null || !PATH_MATCHER.match(pattern, lookupPath)) {
            return Collections.emptyMap();
        }
        return PATH_MATCHER.extractUriTemplateVariables(pattern, lookupPath);
    }

    private static String stripPrefixes(String path, List<String> prefixes) {
        String stripped = path;
        if (prefixes == null || prefixes.isEmpty()) {
            return stripped;
        }
        for (String prefix : prefixes) {
            stripped = stripPrefix(stripped, prefix);
        }
        return stripped;
    }

    private static String stripPrefix(String path, String prefix) {
        if (path == null) {
            return null;
        }
        String normalizedPrefix = normalizePrefix(prefix);
        if (normalizedPrefix == null || normalizedPrefix.isEmpty() || "/".equals(normalizedPrefix)) {
            return path;
        }
        if (!matchesPathPrefix(path, normalizedPrefix)) {
            return path;
        }
        String stripped = path.substring(normalizedPrefix.length());
        if (stripped.isEmpty()) {
            return "/";
        }
        return stripped.startsWith("/") ? stripped : "/" + stripped;
    }

    private static boolean matchesPathPrefix(String path, String prefix) {
        if (!path.startsWith(prefix)) {
            return false;
        }
        return path.length() == prefix.length()
                || prefix.endsWith("/")
                || path.charAt(prefix.length()) == '/';
    }

    private static String normalizePrefix(String value) {
        String text = readString(value);
        if (text == null || text.isEmpty()) {
            return null;
        }
        if (!text.startsWith("/")) {
            text = "/" + text;
        }
        while (text.length() > 1 && text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /**
     * 归一化路径段：解码 {@code %2e} 变体为点、折叠 {@code .} / {@code ..} 段（C10）。
     * <p>
     * 仅解码路径编码的点，不解码其它字符，避免改变分段语义；
     * {@code ..} 段向上回退一段，位于根时视为根（不允许上溯越界）。
     */
    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        String decoded = path.replace("%2e", ".").replace("%2E", ".");
        boolean absolute = decoded.startsWith("/");
        String[] segments = decoded.split("/", -1);
        List<String> stack = new ArrayList<>();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
                continue;
            }
            stack.add(segment);
        }
        String joined = String.join("/", stack);
        if (absolute) {
            joined = "/" + joined;
        }
        return joined.isEmpty() ? "/" : joined;
    }

    private static List<String> resolveForwardedPrefixes(Object request, List<String> trustedForwardedPrefixes) {
        List<String> prefixes = new ArrayList<>();
        if (trustedForwardedPrefixes == null || trustedForwardedPrefixes.isEmpty()) {
            return Collections.emptyList();
        }
        collectForwardedPrefixes(prefixes, readRequestString(request, "X-Forwarded-Prefix"), trustedForwardedPrefixes);
        collectForwardedPrefixes(prefixes, readRequestString(request, "X-Forwarded-Path"), trustedForwardedPrefixes);
        return prefixes.isEmpty() ? Collections.<String>emptyList() : prefixes;
    }

    private static void collectForwardedPrefixes(List<String> prefixes, String rawValue,
            List<String> trustedForwardedPrefixes) {
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return;
        }
        String[] segments = rawValue.split(",");
        for (String segment : segments) {
            String normalized = normalizePrefix(segment);
            if (normalized == null || normalized.isEmpty()) {
                continue;
            }
            // 白名单语义：转发头声明的每个前缀都必须与已配置值完全匹配才采信（C10）
            if (trustedForwardedPrefixes.contains(normalized)) {
                prefixes.add(normalized);
            }
        }
    }

    private static String readRequestString(Object request, String headerName) {
        if (request == null || headerName == null) {
            return null;
        }
        Method method = ReflectionUtils.findMethod(request.getClass(), "getHeader", String.class);
        if (method == null) {
            return null;
        }
        ReflectionUtils.makeAccessible(method);
        Object value = ReflectionUtils.invokeMethod(method, request, headerName);
        return readString(value);
    }

    private static String readString(Object value) {
        return value instanceof String ? ((String) value).trim() : null;
    }

    private static Object invokeNoArgMethod(Object target, String methodName) {
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
}
