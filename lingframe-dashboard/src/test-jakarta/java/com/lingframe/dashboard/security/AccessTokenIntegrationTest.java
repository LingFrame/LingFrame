package com.lingframe.dashboard.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AccessTokenInterceptor 测试")
class AccessTokenIntegrationTest {

    @Test
    @DisplayName("有效 token 应放行")
    void shouldAllowValidToken() throws Exception {
        AccessTokenProperties props = new AccessTokenProperties();
        props.setEnabled(true);
        props.setToken("strong-token-123456");
        AccessTokenInterceptor interceptor = new AccessTokenInterceptor(props);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("X-Access-Token")).thenReturn("strong-token-123456");

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    @DisplayName("无效 token 应 401")
    void shouldRejectInvalidToken() throws Exception {
        AccessTokenProperties props = new AccessTokenProperties();
        props.setEnabled(true);
        props.setToken("strong-token-123456");
        AccessTokenInterceptor interceptor = new AccessTokenInterceptor(props);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(request.getHeader("X-Access-Token")).thenReturn("wrong");
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/lingframe/dashboard/lings");
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
