package com.lingframe.dashboard.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

/**
 * 访问令牌校验决策器。
 * <p>
 * 主干逻辑，与 Servlet 命名空间无关；分支拦截器薄壳从 HttpServletRequest
 * 适配 {@link RequestSnapshot} 后委托本类。
 *
 * @author lingframe
 */
@Slf4j
@RequiredArgsConstructor
public class AccessTokenVerifier {

    private final AccessTokenProperties properties;

    /**
     * 校验请求是否携带有效访问令牌。
     *
     * @param snapshot 请求快照
     * @return 放行决策或 401 终止决策
     */
    public SecurityDecision check(RequestSnapshot snapshot) {
        if (!properties.isEnabled()) {
            return SecurityDecision.proceed();
        }
        String token = snapshot.getHeader("X-Access-Token");
        // 支持标准的 Authorization: Bearer <token> 模式
        if (token == null || token.isEmpty()) {
            String authHeader = snapshot.getHeader("Authorization");
            if (authHeader != null && authHeader.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
                token = authHeader.substring(7).trim();
            }
        }
        
        if (properties.isValidToken(token)) {
            return SecurityDecision.proceed();
        }
        log.warn("Access token verification failed: {} {}", snapshot.getMethod(), snapshot.getRequestUri());
        return SecurityDecision.terminate(401, "application/json;charset=UTF-8",
                "{\"success\":false,\"message\":\"Unauthorized: invalid or missing access token\"}");
    }
}
