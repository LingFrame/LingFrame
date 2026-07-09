package com.lingframe.dashboard.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 安全响应头 Filter 单元测试
 * 覆盖：6 个安全头设置 / HSTS 仅 HTTPS / chain 放行
 */
class SecurityHeadersFilterTest {

    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    private HttpServletRequest mockRequest(boolean secure) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.isSecure()).thenReturn(secure);
        return req;
    }

    @Test
    @DisplayName("HTTPS 请求应设置全部 6 个安全头（含 HSTS）")
    void shouldSetAllHeadersOnHttps() throws Exception {
        HttpServletRequest req = mockRequest(true);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(res).setHeader("X-Frame-Options", "SAMEORIGIN");
        verify(res).setHeader("X-Content-Type-Options", "nosniff");
        verify(res).setHeader("X-XSS-Protection", "1; mode=block");
        verify(res).setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        verify(res).setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        verify(res).setHeader("Content-Security-Policy",
            "default-src 'self'; " +
            "script-src 'self' 'unsafe-inline' 'unsafe-eval' cdn.tailwindcss.com; " +
            "style-src 'self' 'unsafe-inline' cdn.tailwindcss.com; " +
            "font-src 'self' data:; " +
            "img-src 'self' data:; " +
            "connect-src 'self'; " +
            "frame-ancestors 'self'");
        verify(chain).doFilter(req, res);
    }

    @Test
    @DisplayName("HTTP 请求不应设置 HSTS 头")
    void shouldNotSetHstsOnHttp() throws Exception {
        HttpServletRequest req = mockRequest(false);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(res).setHeader("X-Frame-Options", "SAMEORIGIN");
        verify(res).setHeader("X-Content-Type-Options", "nosniff");
        verify(res).setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        org.mockito.Mockito.verify(res, org.mockito.Mockito.never())
                .setHeader(org.mockito.ArgumentMatchers.eq("Strict-Transport-Security"), any());
        verify(chain).doFilter(req, res);
    }
}
