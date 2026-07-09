package com.lingframe.dashboard.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 只读模式拦截器单元测试
 * <p>
 * 覆盖：未启用放行 / 安全方法放行 / 写方法 403 / 白名单前缀放行 / null 安全 / 响应内容契约
 */
class ReadOnlyInterceptorTest {

    private ReadOnlyProperties properties;
    private ReadOnlyInterceptor interceptor;

    @BeforeEach
    void setUp() {
        properties = new ReadOnlyProperties();
        interceptor = new ReadOnlyInterceptor(properties);
    }

    /** 构造请求 mock，使用 lenient 避免不同分支下未使用的桩引发 UnnecessaryStubbingException */
    private HttpServletRequest request(String method, String uri) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        lenient().when(req.getMethod()).thenReturn(method);
        lenient().when(req.getRequestURI()).thenReturn(uri);
        return req;
    }

    /** 拒绝场景需要捕获响应体，桩 getWriter */
    private HttpServletResponse responseWithBody(StringWriter sw) {
        HttpServletResponse response = mock(HttpServletResponse.class);
        try {
            when(response.getWriter()).thenReturn(new PrintWriter(sw));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return response;
    }

    @Nested
    @DisplayName("未启用只读模式")
    class DisabledModeTests {

        @Test
        @DisplayName("未启用时写方法应直接放行，不触碰响应状态")
        void shouldPassWriteMethodWhenDisabled() throws Exception {
            properties.setEnabled(false);
            HttpServletResponse response = mock(HttpServletResponse.class);

            assertTrue(interceptor.preHandle(request("POST", "/lingframe/dashboard/lings"), response, null));
            verify(response, never()).setStatus(anyInt());
        }

        @Test
        @DisplayName("未启用时 DELETE 也应放行")
        void shouldPassDeleteWhenDisabled() throws Exception {
            properties.setEnabled(false);
            assertTrue(interceptor.preHandle(request("DELETE", "/x"), mock(HttpServletResponse.class), null));
        }
    }

    @Nested
    @DisplayName("启用只读模式 - 安全方法放行")
    class SafeMethodTests {

        @BeforeEach
        void enable() {
            properties.setEnabled(true);
        }

        @Test
        @DisplayName("GET 应放行")
        void shouldPassGet() throws Exception {
            assertTrue(interceptor.preHandle(request("GET", "/lingframe/dashboard/lings"),
                    mock(HttpServletResponse.class), null));
        }

        @Test
        @DisplayName("HEAD 应放行")
        void shouldPassHead() throws Exception {
            assertTrue(interceptor.preHandle(request("HEAD", "/lingframe/dashboard/lings"),
                    mock(HttpServletResponse.class), null));
        }

        @Test
        @DisplayName("OPTIONS 应放行（预检请求不被拦截）")
        void shouldPassOptions() throws Exception {
            assertTrue(interceptor.preHandle(request("OPTIONS", "/lingframe/dashboard/lings"),
                    mock(HttpServletResponse.class), null));
        }

        @Test
        @DisplayName("方法名大小写不敏感：get/get 应放行")
        void shouldPassCaseInsensitiveMethod() throws Exception {
            assertTrue(interceptor.preHandle(request("get", "/x"), mock(HttpServletResponse.class), null));
            assertTrue(interceptor.preHandle(request("head", "/x"), mock(HttpServletResponse.class), null));
            assertTrue(interceptor.preHandle(request("options", "/x"), mock(HttpServletResponse.class), null));
        }
    }

    @Nested
    @DisplayName("启用只读模式 - 写方法拦截")
    class WriteMethodTests {

        @BeforeEach
        void enable() {
            properties.setEnabled(true);
        }

        @Test
        @DisplayName("POST 应返回 403 且响应体包含只读模式提示")
        void shouldRejectPost() throws Exception {
            StringWriter sw = new StringWriter();
            HttpServletResponse response = responseWithBody(sw);

            assertFalse(interceptor.preHandle(request("POST", "/lingframe/dashboard/lings"), response, null));
            verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
            assertTrue(sw.toString().contains("只读模式"), "响应体应包含只读模式提示");
        }

        @Test
        @DisplayName("DELETE 应返回 403")
        void shouldRejectDelete() throws Exception {
            HttpServletResponse response = responseWithBody(new StringWriter());
            assertFalse(interceptor.preHandle(request("DELETE", "/lingframe/dashboard/lings/x"), response, null));
            verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        }

        @Test
        @DisplayName("PUT 应返回 403")
        void shouldRejectPut() throws Exception {
            HttpServletResponse response = responseWithBody(new StringWriter());
            assertFalse(interceptor.preHandle(request("PUT", "/lingframe/dashboard/lings/x"), response, null));
            verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        }

        @Test
        @DisplayName("PATCH 应返回 403")
        void shouldRejectPatch() throws Exception {
            HttpServletResponse response = responseWithBody(new StringWriter());
            assertFalse(interceptor.preHandle(request("PATCH", "/lingframe/dashboard/lings/x"), response, null));
            verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        }

        @Test
        @DisplayName("403 响应 Content-Type 应为 application/json;charset=UTF-8")
        void shouldSetJsonContentType() throws Exception {
            HttpServletResponse response = responseWithBody(new StringWriter());
            interceptor.preHandle(request("POST", "/x"), response, null);
            verify(response).setContentType("application/json;charset=UTF-8");
        }

        @Test
        @DisplayName("写方法被拦截后应返回 false，不进入后续处理器")
        void shouldReturnFalseAndBlockChain() throws Exception {
            HttpServletResponse response = responseWithBody(new StringWriter());
            assertFalse(interceptor.preHandle(request("POST", "/x"), response, null));
        }
    }

    @Nested
    @DisplayName("白名单路径放行")
    class AllowedPathTests {

        @BeforeEach
        void enable() {
            properties.setEnabled(true);
        }

        @Test
        @DisplayName("URI 匹配白名单前缀时写方法应放行")
        void shouldPassWhenPathMatchesAllowedPrefix() throws Exception {
            properties.setAllowedPaths(new String[]{"/lingframe/dashboard/health"});
            assertTrue(interceptor.preHandle(
                    request("POST", "/lingframe/dashboard/health/check"),
                    mock(HttpServletResponse.class), null));
        }

        @Test
        @DisplayName("URI 不匹配任何白名单前缀时写方法应返回 403")
        void shouldRejectWhenPathNotMatchAllowedPrefix() throws Exception {
            properties.setAllowedPaths(new String[]{"/lingframe/dashboard/health"});
            HttpServletResponse response = responseWithBody(new StringWriter());
            assertFalse(interceptor.preHandle(
                    request("POST", "/lingframe/dashboard/lings"),
                    response, null));
            verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        }

        @Test
        @DisplayName("白名单为 null 时不应抛 NPE，写方法正常返回 403")
        void shouldNotThrowNpeWhenAllowedPathsNull() throws Exception {
            properties.setAllowedPaths(null);
            HttpServletResponse response = responseWithBody(new StringWriter());
            assertFalse(interceptor.preHandle(
                    request("POST", "/lingframe/dashboard/lings"),
                    response, null));
            verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        }

        @Test
        @DisplayName("白名单前缀匹配为 startsWith 语义，深层子路径也放行")
        void shouldPassDeepSubPathUnderAllowedPrefix() throws Exception {
            properties.setAllowedPaths(new String[]{"/actuator"});
            assertTrue(interceptor.preHandle(
                    request("DELETE", "/actuator/health/deep/sub"),
                    mock(HttpServletResponse.class), null));
        }

        @Test
        @DisplayName("多个白名单前缀中任一匹配即放行")
        void shouldPassWhenAnyOfMultiplePrefixesMatches() throws Exception {
            properties.setAllowedPaths(new String[]{"/actuator", "/lingframe/dashboard/health"});
            assertTrue(interceptor.preHandle(
                    request("POST", "/lingframe/dashboard/health"),
                    mock(HttpServletResponse.class), null));
        }
    }
}
