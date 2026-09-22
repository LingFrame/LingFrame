package com.lingframe.dashboard.security;

import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 访问令牌拦截器（jakarta 栈薄壳）。
 * <p>
 * 只负责 Servlet 适配与响应写出，业务逻辑由 {@link AccessTokenVerifier} 承载。
 *
 * @author lingframe
 */
public class AccessTokenInterceptor implements HandlerInterceptor {

    private final AccessTokenVerifier verifier;

    public AccessTokenInterceptor(AccessTokenProperties properties) {
        this.verifier = new AccessTokenVerifier(properties);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        SecurityDecision decision = verifier.check(new ServletRequestSnapshot(request));
        if (!decision.isProceed()) {
            ServletResponses.applyBody(decision, response);
        }
        return decision.isProceed();
    }
}
