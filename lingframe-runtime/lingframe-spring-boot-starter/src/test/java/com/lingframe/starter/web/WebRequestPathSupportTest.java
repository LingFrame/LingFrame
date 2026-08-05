package com.lingframe.starter.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link WebRequestPathSupport#resolveLookupPath(Object, java.util.List)} 单元测试。
 * <p>
 * 重点覆盖默认 servlet（映射 "/"）形态：容器对不存在显式 servlet 映射的请求，
 * {@code getServletPath()} 会返回整个请求路径而非空，剥离时应保留请求路径本身。
 */
@DisplayName("WebRequestPathSupport 路径解析测试")
class WebRequestPathSupportTest {

    @Test
    @DisplayName("默认 servlet 形态（servletPath 等于整个请求路径）时不应剥离为根路径")
    void shouldKeepRequestUriWhenServletPathEqualsWholeRequestPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/user-ling/user/listUsers");
        request.setServletPath("/user-ling/user/listUsers");

        assertEquals("/user-ling/user/listUsers",
                WebRequestPathSupport.resolveLookupPath(request, Collections.<String>emptyList()));
    }

    @Test
    @DisplayName("真实前缀型 servletPath（短于请求路径）时仍应剥离")
    void shouldStripRealServletPathPrefix() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/gateway/ling-a/demo/42");
        request.setServletPath("/gateway");

        assertEquals("/ling-a/demo/42",
                WebRequestPathSupport.resolveLookupPath(request, Collections.<String>emptyList()));
    }

    @Test
    @DisplayName("contextPath 剥离不受 servletPath 兜底逻辑影响")
    void shouldKeepStrippingContextPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/user-ling/user/listUsers");
        request.setContextPath("/app");

        assertEquals("/user-ling/user/listUsers",
                WebRequestPathSupport.resolveLookupPath(request, Collections.<String>emptyList()));
    }

    @Test
    @DisplayName("无 servletPath 时行为保持不变")
    void shouldWorkWithoutServletPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/user-ling/user/listUsers");

        assertEquals("/user-ling/user/listUsers",
                WebRequestPathSupport.resolveLookupPath(request, Collections.<String>emptyList()));
    }
}
