package com.lingframe.starter.web;

import org.springframework.core.Ordered;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Spring Boot 2.x 上 Ling Web 路由的单入口 HandlerMapping。
 */
public class LingGatewayHandlerMapping extends AbstractHandlerMapping {

    private final WebRouteResolver webRouteResolver;
    private final HandlerMethod gatewayHandlerMethod;

    public LingGatewayHandlerMapping(WebRouteResolver webRouteResolver, WebInterfaceManager webInterfaceManager) {
        this.webRouteResolver = webRouteResolver;
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
        String lookupPath = WebRequestPathSupport.resolveLookupPath(request);
        request.setAttribute(BEST_MATCHING_HANDLER_ATTRIBUTE, gatewayHandlerMethod);
        request.setAttribute(BEST_MATCHING_PATTERN_ATTRIBUTE, metadata.getUrlPattern());
        request.setAttribute(PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, lookupPath);
        Map<String, String> variables = WebRequestPathSupport.extractUriVariables(metadata.getUrlPattern(), lookupPath);
        request.setAttribute(URI_TEMPLATE_VARIABLES_ATTRIBUTE, variables);
        return gatewayHandlerMethod;
    }
}
