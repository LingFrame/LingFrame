package com.lingframe.dashboard.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dashboard CORS 配置属性
 * <p>
 * 生产环境应显式设置 {@code allowed-origins}，不要依赖通配默认值。
 * 当 access-token 认证开启且未配置允许源时，仅允许同源请求。
 */
@Data
@ConfigurationProperties(prefix = "lingframe.dashboard.cors")
public class CorsProperties {

    /**
     * 是否启用 CORS 过滤
     * 设为 false 时整个 Filter 跳过（开发逃生口）
     */
    private boolean enabled = true;

    /**
     * 允许的跨域源列表
     * <ul>
     *   <li>空列表 + access-token 未启用 = 宽松模式（开发环境，允许所有源）</li>
     *   <li>空列表 + access-token 已启用 = 仅同源（生产安全默认值）</li>
     *   <li>非空列表 = 仅允许列表中列出的源</li>
     * </ul>
     */
    private List<String> allowedOrigins = new ArrayList<>();

    /**
     * CORS 允许的 HTTP 方法
     */
    private List<String> allowedMethods = Arrays.asList("GET", "POST", "DELETE", "OPTIONS");

    /**
     * 允许的请求头
     */
    private List<String> allowedHeaders = Arrays.asList(
            "Content-Type", "X-Access-Token", "X-Requested-With");

    /**
     * 预检请求缓存时间（秒）
     */
    private long maxAge = 3600;
}
