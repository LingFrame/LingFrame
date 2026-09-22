package com.lingframe.dashboard.security;

import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 只读模式拦截器（jakarta 栈薄壳）。
 * <p>
 * 只负责 Servlet 适配与响应写出，业务逻辑由 {@link ReadOnlyPolicy} 承载。
 *
 * @author lingframe
 */
public class ReadOnlyInterceptor implements HandlerInterceptor {

    private final ReadOnlyPolicy policy;

    public ReadOnlyInterceptor(ReadOnlyProperties properties) {
        this.policy = new ReadOnlyPolicy(properties);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        SecurityDecision decision = policy.check(new ServletRequestSnapshot(request));
        if (!decision.isProceed()) {
            ServletResponses.applyBody(decision, response);
        }
        return decision.isProceed();
    }
}
