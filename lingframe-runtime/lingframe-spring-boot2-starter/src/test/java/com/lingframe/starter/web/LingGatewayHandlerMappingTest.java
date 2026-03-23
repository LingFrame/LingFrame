package com.lingframe.starter.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LingGatewayHandlerMapping 测试")
class LingGatewayHandlerMappingTest {

    @Mock
    private WebRouteResolver webRouteResolver;
    @Mock
    private WebInterfaceManager webInterfaceManager;

    @Test
    @DisplayName("暴露路径变量前应去除 context 与 servlet path")
    void shouldStripContextAndServletPathBeforeExposingPathVariables() throws Exception {
        Method targetMethod = DemoController.class.getMethod("detail", String.class);
        WebInterfaceMetadata metadata = WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .version("v1")
                .targetBean(new DemoController())
                .targetMethod(targetMethod)
                .classLoader(DemoController.class.getClassLoader())
                .urlPattern("/ling-a/demo/{id}")
                .httpMethod("GET")
                .requiredPermission("demo:read")
                .build();
        WebRouteResolution resolution = new WebRouteResolution("GET#/ling-a/demo/{id}", metadata, null, null);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/gateway/ling-a/demo/42");
        request.setContextPath("/app");
        request.setServletPath("/gateway");
        when(webInterfaceManager.gatewayHandler())
                .thenReturn(new WebInterfaceManager.LingGatewayHandler(webInterfaceManager));
        when(webRouteResolver.resolveRoute(request)).thenReturn(resolution);

        LingGatewayHandlerMapping mapping = new LingGatewayHandlerMapping(webRouteResolver, webInterfaceManager);

        Object handler = mapping.getHandler(request).getHandler();
        assertInstanceOf(HandlerMethod.class, handler);
        assertEquals("/ling-a/demo/42", request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE));

        Object attr = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        assertNotNull(attr);
        assertTrue(attr instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, String> uriVariables = (Map<String, String>) attr;
        assertEquals("42", uriVariables.get("id"));
    }

    @Test
    @DisplayName("暴露路径变量前应去除 forwarded prefix")
    void shouldStripForwardedPrefixBeforeExposingPathVariables() throws Exception {
        Method targetMethod = DemoController.class.getMethod("detail", String.class);
        WebInterfaceMetadata metadata = WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .version("v1")
                .targetBean(new DemoController())
                .targetMethod(targetMethod)
                .classLoader(DemoController.class.getClassLoader())
                .urlPattern("/ling-a/demo/{id}")
                .httpMethod("GET")
                .requiredPermission("demo:read")
                .build();
        WebRouteResolution resolution = new WebRouteResolution("GET#/ling-a/demo/{id}", metadata, null, null);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/proxy/ling-a/demo/42");
        request.addHeader("X-Forwarded-Prefix", "/proxy");
        when(webInterfaceManager.gatewayHandler())
                .thenReturn(new WebInterfaceManager.LingGatewayHandler(webInterfaceManager));
        when(webRouteResolver.resolveRoute(request)).thenReturn(resolution);

        LingGatewayHandlerMapping mapping = new LingGatewayHandlerMapping(webRouteResolver, webInterfaceManager);

        Object handler = mapping.getHandler(request).getHandler();
        assertInstanceOf(HandlerMethod.class, handler);
        assertEquals("/ling-a/demo/42", request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE));

        Object attr = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        assertNotNull(attr);
        assertTrue(attr instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, String> uriVariables = (Map<String, String>) attr;
        assertEquals("42", uriVariables.get("id"));
    }

    static class DemoController {
        public String detail(String id) {
            return id;
        }
    }
}
