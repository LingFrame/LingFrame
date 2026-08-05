package com.lingframe.starter.web;

/**
 * Web 请求（request attribute）魔法键集中常量。
 * <p>
 * 这些 request attribute 键被 {@link WebInterfaceManager}、{@link DefaultWebRouteResolver}、
 * boot2/boot3 的 {@code LingWebGovernanceFilter} 及测试跨类复用。
 * 收敛到独立常量类后，所有引用方统一从 {@code WebRequestKeys} 读取，避免对具体类的耦合。
 */
public final class WebRequestKeys {

    /** 请求级缓存的 Web 接口元数据键 */
    public static final String METADATA = "ling.web.metadata";

    /** 请求级缓存的路由解析结果键 */
    public static final String ROUTE_RESOLUTION = "ling.web.route.resolution";

    /** 请求级强制目标版本键（灰度/指定版本路由） */
    public static final String TARGET_VERSION = "ling.target.version";

    private WebRequestKeys() {
    }
}
