package com.lingframe.dashboard.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dashboard CORS + CSRF 过滤器单元测试
 * 覆盖：路径过滤 / cors 开关 / OPTIONS 预检 / 实际请求 origin 校验 /
 *      CSRF 状态变更防护 / 同源判断（scheme+host+port）/ 默认端口等价 / 非法 URI
 */
class DashboardCorsFilterTest {

    /** 构造过滤器：corsProps + tokenProps 可独立配置 */
    private DashboardCorsFilter filter(java.util.List<String> allowedOrigins, boolean corsEnabled, boolean tokenEnabled) {
        CorsProperties cors = mock(CorsProperties.class);
        when(cors.isEnabled()).thenReturn(corsEnabled);
        when(cors.getAllowedOrigins()).thenReturn(allowedOrigins);
        when(cors.getAllowedMethods()).thenReturn(Arrays.asList("GET", "POST", "DELETE", "OPTIONS"));
        when(cors.getAllowedHeaders()).thenReturn(Arrays.asList("Content-Type", "X-Access-Token"));
        when(cors.getMaxAge()).thenReturn(3600L);
        AccessTokenProperties token = mock(AccessTokenProperties.class);
        when(token.isEnabled()).thenReturn(tokenEnabled);
        return new DashboardCorsFilter(cors, token);
    }

    private HttpServletRequest req(String path, String method, String origin, String requestUrl) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getRequestURI()).thenReturn(path);
        when(r.getMethod()).thenReturn(method);
        when(r.getHeader("Origin")).thenReturn(origin);
        if (requestUrl != null) {
            when(r.getRequestURL()).thenReturn(new StringBuffer(requestUrl));
        }
        return r;
    }

    private HttpServletResponse res() throws Exception {
        HttpServletResponse r = mock(HttpServletResponse.class);
        when(r.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return r;
    }

    @Nested
    @DisplayName("路径过滤与开关")
    class PathAndSwitchTests {
        @Test
        @DisplayName("非 dashboard 路径应直接放行，不设置任何 CORS 头")
        void shouldBypassNonDashboardPath() throws Exception {
            DashboardCorsFilter f = filter(Collections.emptyList(), true, false);
            HttpServletRequest req = req("/api/other", "GET", "https://evil.com", "http://localhost/api/other");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(chain).doFilter(req, res);
            verify(res, never()).setHeader(eq("Access-Control-Allow-Origin"), any());
        }

        @Test
        @DisplayName("cors 未启用应直接放行")
        void shouldBypassWhenCorsDisabled() throws Exception {
            DashboardCorsFilter f = filter(Collections.emptyList(), false, false);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "GET", "https://evil.com", "http://localhost/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(chain).doFilter(req, res);
            verify(res, never()).setHeader(eq("Access-Control-Allow-Origin"), any());
        }
    }

    @Nested
    @DisplayName("OPTIONS 预检")
    class PreflightTests {
        @Test
        @DisplayName("预检 + origin 在显式列表 → 设置 CORS 头 + 200，不调用 chain")
        void shouldSetCorsHeadersForAllowedPreflight() throws Exception {
            DashboardCorsFilter f = filter(Arrays.asList("https://app.com"), true, false);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "OPTIONS", "https://app.com", "http://localhost/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(res).setHeader("Access-Control-Allow-Origin", "https://app.com");
            verify(res).setHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
            verify(res).setHeader("Access-Control-Allow-Headers", "Content-Type, X-Access-Token");
            verify(res).setHeader("Access-Control-Max-Age", "3600");
            verify(res, atLeastOnce()).setHeader("Vary", "Origin");
            verify(res).setStatus(HttpServletResponse.SC_OK);
            verify(chain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("预检 + origin 不在显式列表 → 仅 Vary + 200，不设置 CORS 头")
        void shouldNotSetCorsHeadersForDisallowedPreflight() throws Exception {
            DashboardCorsFilter f = filter(Arrays.asList("https://app.com"), true, false);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "OPTIONS", "https://evil.com", "http://localhost/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(res, atLeastOnce()).setHeader("Vary", "Origin");
            verify(res, never()).setHeader(eq("Access-Control-Allow-Origin"), any());
            verify(res).setStatus(HttpServletResponse.SC_OK);
        }

        @Test
        @DisplayName("预检 + 无 Origin 头 → 仅 Vary + 200")
        void shouldHandlePreflightWithoutOrigin() throws Exception {
            DashboardCorsFilter f = filter(Collections.emptyList(), true, false);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "OPTIONS", null, "http://localhost/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(res, atLeastOnce()).setHeader("Vary", "Origin");
            verify(res, never()).setHeader(eq("Access-Control-Allow-Origin"), any());
            verify(res).setStatus(HttpServletResponse.SC_OK);
        }

        @Test
        @DisplayName("预检 + 同源（空列表）→ 设置 CORS 头 + 200")
        void shouldAllowSameOriginPreflight() throws Exception {
            DashboardCorsFilter f = filter(Collections.emptyList(), true, false);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "OPTIONS", "http://localhost:8080", "http://localhost:8080/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(res).setHeader("Access-Control-Allow-Origin", "http://localhost:8080");
            verify(res).setStatus(HttpServletResponse.SC_OK);
        }

        @Test
        @DisplayName("显式列表匹配应大小写不敏感")
        void shouldMatchOriginCaseInsensitively() throws Exception {
            DashboardCorsFilter f = filter(Arrays.asList("https://APP.com"), true, false);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "OPTIONS", "https://app.com", "http://localhost/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(res).setHeader("Access-Control-Allow-Origin", "https://app.com");
        }
    }

    @Nested
    @DisplayName("实际请求 origin 校验")
    class ActualRequestTests {
        @Test
        @DisplayName("GET + origin 在显式列表 → 设置 CORS 头 + 放行")
        void shouldAllowGetWithAllowedOrigin() throws Exception {
            DashboardCorsFilter f = filter(Arrays.asList("https://app.com"), true, false);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "GET", "https://app.com", "http://localhost/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(res).setHeader("Access-Control-Allow-Origin", "https://app.com");
            verify(chain).doFilter(req, res);
        }

        @Test
        @DisplayName("GET + origin 不在显式列表 → 403")
        void shouldRejectGetWithDisallowedOrigin() throws Exception {
            DashboardCorsFilter f = filter(Arrays.asList("https://app.com"), true, false);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "GET", "https://evil.com", "http://localhost/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(res).setStatus(HttpServletResponse.SC_FORBIDDEN);
            verify(chain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("GET + 跨域 + 空列表 + token 未启用 → 开发模式放行 + CORS 头")
        void shouldAllowCrossOriginInDevMode() throws Exception {
            DashboardCorsFilter f = filter(Collections.emptyList(), true, false);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "GET", "https://dev.com", "http://localhost/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(res).setHeader("Access-Control-Allow-Origin", "https://dev.com");
            verify(chain).doFilter(req, res);
        }

        @Test
        @DisplayName("GET + 跨域 + 空列表 + token 启用 → 403（生产安全默认）")
        void shouldRejectCrossOriginWhenTokenEnabledAndNoAllowedList() throws Exception {
            DashboardCorsFilter f = filter(Collections.emptyList(), true, true);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "GET", "https://evil.com", "http://localhost/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(res).setStatus(HttpServletResponse.SC_FORBIDDEN);
            verify(chain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("GET + 同源 + 空列表 + token 启用 → 放行 + CORS 头")
        void shouldAllowSameOriginWithTokenEnabled() throws Exception {
            DashboardCorsFilter f = filter(Collections.emptyList(), true, true);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "GET", "http://localhost:8080", "http://localhost:8080/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(res).setHeader("Access-Control-Allow-Origin", "http://localhost:8080");
            verify(chain).doFilter(req, res);
        }

        @Test
        @DisplayName("GET 无 Origin 头 → 放行（非状态变更，不触发 CSRF）")
        void shouldAllowGetWithoutOrigin() throws Exception {
            DashboardCorsFilter f = filter(Collections.emptyList(), true, true);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "GET", null, "http://localhost:8080/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(chain).doFilter(req, res);
        }
    }

    @Nested
    @DisplayName("CSRF 状态变更防护")
    class CsrfTests {
        @Test
        @DisplayName("POST + token 启用 + 无 Origin → 403 Missing Origin header")
        void shouldRejectPostWithoutOriginWhenTokenEnabled() throws Exception {
            DashboardCorsFilter f = filter(Collections.emptyList(), true, true);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "POST", null, "http://localhost:8080/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(res).setStatus(HttpServletResponse.SC_FORBIDDEN);
            verify(chain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("POST + token 启用 + 同源 Origin → 放行")
        void shouldAllowPostWithSameOriginWhenTokenEnabled() throws Exception {
            DashboardCorsFilter f = filter(Collections.emptyList(), true, true);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "POST", "http://localhost:8080", "http://localhost:8080/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(chain).doFilter(req, res);
        }

        @Test
        @DisplayName("POST + token 未启用 + 无 Origin → 放行（不触发 CSRF）")
        void shouldAllowPostWithoutOriginWhenTokenDisabled() throws Exception {
            DashboardCorsFilter f = filter(Collections.emptyList(), true, false);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "POST", null, "http://localhost:8080/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(chain).doFilter(req, res);
        }

        @Test
        @DisplayName("DELETE/PUT/PATCH 同样受 CSRF 防护")
        void shouldProtectAllStateChangingMethods() throws Exception {
            DashboardCorsFilter f = filter(Collections.emptyList(), true, true);
            for (String m : Arrays.asList("DELETE", "PUT", "PATCH")) {
                HttpServletRequest req = req("/lingframe/dashboard/lings", m, null, "http://localhost:8080/x");
                HttpServletResponse res = res();
                FilterChain chain = mock(FilterChain.class);
                f.doFilter(req, res, chain);
                verify(res).setStatus(HttpServletResponse.SC_FORBIDDEN);
            }
        }
    }

    @Nested
    @DisplayName("同源判断（scheme + host + port）")
    class SameOriginTests {
        @Test
        @DisplayName("不同 host 应判为异源 → 跨域拒绝")
        void shouldRejectDifferentHost() throws Exception {
            DashboardCorsFilter f = filter(Collections.emptyList(), true, true);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "GET", "http://evil.com", "http://localhost:8080/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(res).setStatus(HttpServletResponse.SC_FORBIDDEN);
        }

        @Test
        @DisplayName("不同 scheme（http vs https）应判为异源")
        void shouldRejectDifferentScheme() throws Exception {
            DashboardCorsFilter f = filter(Collections.emptyList(), true, true);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "GET", "https://localhost:8080", "http://localhost:8080/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(res).setStatus(HttpServletResponse.SC_FORBIDDEN);
        }

        @Test
        @DisplayName("http 默认端口等价：origin 省略 80 vs 请求显式 80 → 同源")
        void shouldTreatHttpDefaultPortAsEquivalent() throws Exception {
            DashboardCorsFilter f = filter(Collections.emptyList(), true, true);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "GET", "http://localhost", "http://localhost:80/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(chain).doFilter(req, res);
            verify(res).setHeader("Access-Control-Allow-Origin", "http://localhost");
        }

        @Test
        @DisplayName("https 默认端口等价：origin 443 vs 请求省略端口 → 同源")
        void shouldTreatHttpsDefaultPortAsEquivalent() throws Exception {
            DashboardCorsFilter f = filter(Collections.emptyList(), true, true);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "GET", "https://localhost:443", "https://localhost/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(chain).doFilter(req, res);
        }

        @Test
        @DisplayName("不同端口应判为异源")
        void shouldRejectDifferentPort() throws Exception {
            DashboardCorsFilter f = filter(Collections.emptyList(), true, true);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "GET", "http://localhost:9090", "http://localhost:8080/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(res).setStatus(HttpServletResponse.SC_FORBIDDEN);
        }

        @Test
        @DisplayName("非法 Origin URI 应保守拒绝（判为异源）")
        void shouldRejectMalformedOrigin() throws Exception {
            DashboardCorsFilter f = filter(Collections.emptyList(), true, true);
            HttpServletRequest req = req("/lingframe/dashboard/lings", "GET", "###not-a-uri###", "http://localhost:8080/x");
            HttpServletResponse res = res();
            FilterChain chain = mock(FilterChain.class);
            f.doFilter(req, res, chain);
            verify(res).setStatus(HttpServletResponse.SC_FORBIDDEN);
        }
    }
}
