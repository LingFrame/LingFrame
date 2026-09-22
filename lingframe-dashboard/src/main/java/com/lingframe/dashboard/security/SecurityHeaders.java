package com.lingframe.dashboard.security;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 安全响应头配置。
 * <p>
 * 主干工具类，与 Servlet 命名空间无关。分支 {@code SecurityHeadersFilter} 薄壳
 * 调用 {@link #buildHeaders(boolean)} 取得头映射后 {@code setHeader} 到响应。
 *
 * @author lingframe
 */
public final class SecurityHeaders {

    private SecurityHeaders() {
    }

    /**
     * 构建安全响应头映射。
     *
     * @param secure 请求是否 HTTPS（影响是否加 Strict-Transport-Security）
     * @return header 名→值（保持设置顺序）
     */
    public static Map<String, String> buildHeaders(boolean secure) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Frame-Options", "SAMEORIGIN");
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("X-XSS-Protection", "1; mode=block");
        if (secure) {
            headers.put("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        headers.put("Referrer-Policy", "strict-origin-when-cross-origin");
        headers.put("Content-Security-Policy",
                "default-src 'self'; "
                        + "script-src 'self' 'unsafe-inline' 'unsafe-eval' cdn.tailwindcss.com; "
                        + "style-src 'self' 'unsafe-inline' cdn.tailwindcss.com; "
                        + "font-src 'self' data:; "
                        + "img-src 'self' data:; "
                        + "connect-src 'self'; "
                        + "frame-ancestors 'self'");
        return headers;
    }
}
