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

    public static String resolveLookupPath(Object request) {
        Object uri = invokeNoArgMethod(request, "getRequestURI");
        if (!(uri instanceof String)) {
            return null;
        }

        String requestUri = (String) uri;
        requestUri = stripPrefixes(requestUri, resolveForwardedPrefixes(request));

        String contextPath = readString(invokeNoArgMethod(request, "getContextPath"));
        requestUri = stripPrefix(requestUri, contextPath);

        String servletPath = readString(invokeNoArgMethod(request, "getServletPath"));
        requestUri = stripPrefix(requestUri, servletPath);

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

    private static List<String> resolveForwardedPrefixes(Object request) {
        List<String> prefixes = new ArrayList<>();
        collectForwardedPrefixes(prefixes, readRequestString(request, "X-Forwarded-Prefix"));
        collectForwardedPrefixes(prefixes, readRequestString(request, "X-Forwarded-Path"));
        return prefixes.isEmpty() ? Collections.<String>emptyList() : prefixes;
    }

    private static void collectForwardedPrefixes(List<String> prefixes, String rawValue) {
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return;
        }
        String[] segments = rawValue.split(",");
        for (String segment : segments) {
            String normalized = normalizePrefix(segment);
            if (normalized != null && !normalized.isEmpty()) {
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
