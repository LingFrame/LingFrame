package com.lingframe.dashboard.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DashboardCorsFilter 测试")
class DashboardCorsFilterTest {

    @Test
    @DisplayName("非 dashboard 路径应放行")
    void shouldPassNonDashboardPath() throws Exception {
        CorsProperties cors = new CorsProperties();
        AccessTokenProperties token = new AccessTokenProperties();
        token.setEnabled(false);
        DashboardCorsFilter filter = new DashboardCorsFilter(cors, token);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/api/other");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }
}
