package com.lingframe.dashboard.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Servlet 请求快照适配器（jakarta 栈）。
 * <p>
 * 把 HttpServletRequest 适配为命名空间无关的 {@link RequestSnapshot}，
 * 供主干决策器使用。javax 栈有同名实现。
 *
 * @author lingframe
 */
public class ServletRequestSnapshot implements RequestSnapshot {

    private final HttpServletRequest request;

    public ServletRequestSnapshot(HttpServletRequest request) {
        this.request = request;
    }

    @Override
    public String getMethod() {
        return request.getMethod();
    }

    @Override
    public String getRequestUri() {
        return request.getRequestURI();
    }

    @Override
    public String getRequestUrl() {
        return request.getRequestURL().toString();
    }

    @Override
    public boolean isSecure() {
        return request.isSecure();
    }

    @Override
    public String getRemoteAddr() {
        return request.getRemoteAddr();
    }

    @Override
    public String getHeader(String name) {
        return request.getHeader(name);
    }
}
