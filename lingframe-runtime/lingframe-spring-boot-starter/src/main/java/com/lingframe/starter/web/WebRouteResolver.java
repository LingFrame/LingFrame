package com.lingframe.starter.web;

import org.springframework.web.method.HandlerMethod;

/**
 * 将 Web 请求解析为具体的 LingFrame 路由选择。
 */
public interface WebRouteResolver {

    WebRouteResolution resolveRoute(Object request);

    WebRouteResolution resolveRoute(Object request, HandlerMethod handlerMethod);

    WebRouteResolution resolveRoute(String routeKey, Object request);
}
