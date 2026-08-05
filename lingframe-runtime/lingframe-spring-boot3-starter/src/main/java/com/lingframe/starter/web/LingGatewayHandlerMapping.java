package com.lingframe.starter.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot 3.x 上 Ling Web 路由的单入口 HandlerMapping。
 * <p>
 * 转发前缀剥离（C10）必须显式携带 {@code trustedForwardedPrefixes} 白名单：
 * 空白名单表示不采信任何客户端转发头，路径属性与 {@code DefaultWebRouteResolver} 保持同一判定。
 */
public class LingGatewayHandlerMapping extends AbstractHandlerMapping {

    private final WebRouteResolver webRouteResolver;
    private final HandlerMethod gatewayHandlerMethod;
    private final List<String> trustedForwardedPrefixes;

    public LingGatewayHandlerMapping(WebRouteResolver webRouteResolver, WebInterfaceManager webInterfaceManager,
            List<String> trustedForwardedPrefixes) {
        this.webRouteResolver = webRouteResolver;
        this.trustedForwardedPrefixes = trustedForwardedPrefixes != null
                ? trustedForwardedPrefixes
                : Collections.<String>emptyList();
        Method dispatchMethod = ReflectionUtils.findMethod(WebInterfaceManager.LingGatewayHandler.class, "dispatch",
                ServletWebRequest.class);
        if (dispatchMethod == null) {
            throw new IllegalStateException("dispatch method not found for LingGatewayHandler");
        }
        this.gatewayHandlerMethod = new HandlerMethod(webInterfaceManager.gatewayHandler(), dispatchMethod);
        setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    }

    @Override
    protected Object getHandlerInternal(HttpServletRequest request) {
        WebRouteResolution resolution = webRouteResolver.resolveRoute(request);
        if (resolution == null || resolution.getMetadata() == null) {
            return null;
        }

        WebInterfaceMetadata metadata = resolution.getMetadata();
        String lookupPath = WebRequestPathSupport.resolveLookupPath(request, trustedForwardedPrefixes);
        request.setAttribute(BEST_MATCHING_HANDLER_ATTRIBUTE, gatewayHandlerMethod);
        request.setAttribute(BEST_MATCHING_PATTERN_ATTRIBUTE, metadata.getUrlPattern());
        request.setAttribute(PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, lookupPath);
        Map<String, String> variables = WebRequestPathSupport.extractUriVariables(metadata.getUrlPattern(), lookupPath);
        request.setAttribute(URI_TEMPLATE_VARIABLES_ATTRIBUTE, variables);
        return gatewayHandlerMethod;
    }
}
