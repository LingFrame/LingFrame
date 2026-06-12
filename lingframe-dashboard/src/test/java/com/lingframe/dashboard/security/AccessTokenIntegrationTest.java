package com.lingframe.dashboard.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AccessToken 拦截器单元测试
 */
class AccessTokenIntegrationTest {

    private AccessTokenInterceptor interceptor;
    private AccessTokenProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AccessTokenProperties();
        properties.setEnabled(true);
        properties.setToken("secret123");
        interceptor = new AccessTokenInterceptor(properties);
    }

    @Test
    void requestWithoutToken_returns401() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter sw = new StringWriter();
        when(request.getHeader("X-Access-Token")).thenReturn(null);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/lingframe/dashboard/lings");
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void requestWithValidTokenInHeader_passes() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("X-Access-Token")).thenReturn("secret123");

        boolean result = interceptor.preHandle(request, response, null);

        assertTrue(result);
    }

    @Test
    void requestWithInvalidToken_returns401() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter sw = new StringWriter();
        when(request.getHeader("X-Access-Token")).thenReturn("wrong");
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/lingframe/dashboard/lings");
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void disabledToken_passesAll() throws Exception {
        AccessTokenProperties disabledProps = new AccessTokenProperties();
        disabledProps.setEnabled(false);
        AccessTokenInterceptor disabledInterceptor = new AccessTokenInterceptor(disabledProps);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("X-Access-Token")).thenReturn(null);

        assertTrue(disabledInterceptor.preHandle(request, response, null));
    }

    @Test
    void secondaryToken_passes() throws Exception {
        properties.setSecondaryTokens(java.util.Arrays.asList("backup-token"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("X-Access-Token")).thenReturn("backup-token");

        assertTrue(interceptor.preHandle(request, response, null));
    }

    @Test
    void urlParameterToken_ignored() throws Exception {
        // URL 参数方式已禁用，即使传了正确 token 也应返回 401
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter sw = new StringWriter();
        when(request.getHeader("X-Access-Token")).thenReturn(null);
        when(request.getParameter("token")).thenReturn("secret123");
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/lingframe/dashboard/lings");
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
    }
}
