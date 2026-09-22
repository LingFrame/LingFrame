package com.lingframe.dashboard.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 只读模式配置属性
 * 启用后，所有写操作（POST/DELETE）将被拒绝，仅允许 GET 请求
 */
@Data
@ConfigurationProperties(prefix = "lingframe.dashboard.readonly")
public class ReadOnlyProperties {

    /**
     * 是否启用只读模式
     */
    private boolean enabled = false;

    /**
     * 只读模式下允许的路径（前缀匹配），如健康检查等
     */
    private String[] allowedPaths = {};
}
