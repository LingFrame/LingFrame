package com.lingframe.dashboard.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 只读模式决策器。
 * <p>
 * 主干逻辑，与 Servlet 命名空间无关；分支拦截器薄壳委托本类。
 *
 * @author lingframe
 */
@Slf4j
@RequiredArgsConstructor
public class ReadOnlyPolicy {

    private final ReadOnlyProperties properties;

    /**
     * 判断写操作是否允许在只读模式下执行。
     *
     * @param snapshot 请求快照
     * @return 放行决策或 403 终止决策
     */
    public SecurityDecision check(RequestSnapshot snapshot) {
        if (!properties.isEnabled()) {
            return SecurityDecision.proceed();
        }
        String method = snapshot.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)) {
            return SecurityDecision.proceed();
        }
        String uri = snapshot.getRequestUri();
        if (properties.getAllowedPaths() != null) {
            for (String allowed : properties.getAllowedPaths()) {
                if (uri.startsWith(allowed)) {
                    return SecurityDecision.proceed();
                }
            }
        }
        log.warn("Write operation rejected in read-only mode: {} {}", method, uri);
        return SecurityDecision.terminate(403, "application/json;charset=UTF-8",
                "{\"success\":false,\"message\":\"当前为只读模式，写操作已禁用\"}");
    }
}
