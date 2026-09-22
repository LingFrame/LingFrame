package com.lingframe.dashboard.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限流 Filter 基础测试。
 */
@DisplayName("RateLimitFilter 测试")
class RateLimitFilterTest {

    private RateLimitFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setMaxRequestsPerSecond(30);
        filter = new RateLimitFilter(properties);
        chain = mock(FilterChain.class);
    }

    @Test
    @DisplayName("非 dashboard 路径应直接放行")
    void shouldPassNonDashboardPath() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/api/other");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    @DisplayName("UI 路径应直接放行")
    void shouldPassUiPath() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/lingframe/dashboard/ui/index.html");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("API 路径令牌耗尽后应返回 429")
    void shouldRejectWhenBucketExhausted() throws Exception {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setMaxRequestsPerSecond(1);
        filter = new RateLimitFilter(properties);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(request.getRequestURI()).thenReturn("/lingframe/dashboard/lings");
        when(request.getRemoteAddr()).thenReturn("1.1.1.1");
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilter(request, response, chain);
        filter.doFilter(request, response, chain);

        verify(response).setStatus(429);
        assertTrue(body.toString().contains("请求过于频繁"));
    }
}
