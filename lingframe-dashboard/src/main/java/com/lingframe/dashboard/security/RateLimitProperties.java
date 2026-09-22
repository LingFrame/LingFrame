package com.lingframe.dashboard.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 限流配置属性
 * <p>
 * 默认不信任任何反向代理头（X-Forwarded-For / X-Real-IP），
 * 仅当直连来自 {@link #trustedProxyIps} 中的 IP 时才解析代理头。
 * 这避免攻击者伪造 X-Forwarded-For 绕过限流。
 */
@Data
@ConfigurationProperties(prefix = "lingframe.dashboard.rate-limit")
public class RateLimitProperties {

    /**
     * 受信反向代理 IP 集合。
     * <p>
     * 仅当请求的 TCP 直连 IP（{@code request.getRemoteAddr()}）在此集合中时，
     * 才解析 X-Forwarded-For 头取原始客户端 IP。默认空集，即不信任任何代理。
     */
    private Set<String> trustedProxyIps = Collections.emptySet();

    /**
     * 每秒每 IP 最大请求数。
     */
    private int maxRequestsPerSecond = 30;

    /**
     * IP 不活跃超过此时间（毫秒）后清理。
     */
    private long ipIdleThresholdMs = 600_000L;

    /**
     * 判断 IP 是否受信代理。
     */
    public boolean isTrustedProxy(String ip) {
        return ip != null && trustedProxyIps.contains(ip);
    }

    /**
     * 设置受信代理 IP（用于测试与编程式配置）。
     */
    public void setTrustedProxyIps(Set<String> ips) {
        if (ips == null) {
            this.trustedProxyIps = Collections.emptySet();
        } else {
            this.trustedProxyIps = Collections.unmodifiableSet(new HashSet<>(ips));
        }
    }
}
