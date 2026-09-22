package com.lingframe.dashboard.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ReadOnlyInterceptor 测试")
class ReadOnlyInterceptorTest {

    @Test
    @DisplayName("未启用时应放行写操作")
    void shouldAllowWhenDisabled() throws Exception {
        ReadOnlyProperties props = new ReadOnlyProperties();
        props.setEnabled(false);
        ReadOnlyInterceptor interceptor = new ReadOnlyInterceptor(props);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/lingframe/dashboard/lings");

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    @DisplayName("启用后 POST 应拒绝")
    void shouldRejectPostWhenEnabled() throws Exception {
        ReadOnlyProperties props = new ReadOnlyProperties();
        props.setEnabled(true);
        ReadOnlyInterceptor interceptor = new ReadOnlyInterceptor(props);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/lingframe/dashboard/lings");
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }
}
