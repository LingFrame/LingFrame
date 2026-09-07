package com.lingframe.starter.filter;

import com.lingframe.api.security.AuditMetadataKeys;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.spi.RoutableTarget;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.governance.EntryInvocationGovernanceResolver;
import com.lingframe.starter.web.WebInterfaceMetadata;
import com.lingframe.starter.web.WebRequestKeys;
import com.lingframe.starter.web.WebRouteResolution;
import com.lingframe.starter.web.WebRouteResolver;
import jakarta.servlet.FilterChain;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@ExtendWith(MockitoExtension.class)
@DisplayName("LingWebGovernanceFilter 测试")
class LingWebGovernanceFilterTest {

    // 重新编译，用于验证 boot3 servlet 路径上的元数据透传。

    @Mock
    private WebRouteResolver webRouteResolver;
    @Mock
    private InvocationPipelineEngine pipelineEngine;
    @Mock
    private RequestMappingHandlerMapping requestMappingHandlerMapping;
    @Mock
    private FilterChain filterChain;
    @Mock
    private LingRuntime runtime;
    @Mock
    private LingInstance targetInstance;
    @Mock
    private EntryInvocationGovernanceResolver invocationGovernanceResolver;

    @Test
    @DisplayName("应使用灵元元数据中的目标方法与预解析实例")
    void shouldUseLingMetadataTargetMethodAndPreResolvedInstance() throws Exception {
        LingFrameProperties properties = new LingFrameProperties();
        properties.getLingCoreGovernance().setEnabled(true);
        LingWebGovernanceFilter filter = new LingWebGovernanceFilter(
                webRouteResolver, pipelineEngine, properties, requestMappingHandlerMapping, null,
                invocationGovernanceResolver);

        DemoController controller = new DemoController();
        Method targetMethod = DemoController.class.getMethod("detail");
        WebInterfaceMetadata metadata = WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .version("v1")
                .targetBean(controller)
                .targetMethod(targetMethod)
                .classLoader(DemoController.class.getClassLoader())
                .urlPattern("/ling-a/demo/detail")
                .httpMethod("GET")
                .requiredPermission("demo:read")
                .shouldAudit(false)
                .auditAction("DETAIL")
                .build();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ling-a/demo/detail");
        MockHttpServletResponse response = new MockHttpServletResponse();
        WebRouteResolution resolution = new WebRouteResolution(
                "GET#/ling-a/demo/detail", metadata, runtime, targetInstance);

        when(webRouteResolver.resolveRoute(any())).thenAnswer(invocation -> {
            request.setAttribute(WebRequestKeys.ROUTE_RESOLUTION, resolution);
            request.setAttribute(WebRequestKeys.METADATA, metadata);
            request.setAttribute(WebRequestKeys.TARGET_VERSION, metadata.getVersion());
            return resolution;
        });
        when(targetInstance.getVersion()).thenReturn("v1");

        AtomicReference<String> observedMethodName = new AtomicReference<>();
        AtomicReference<String> observedTargetClass = new AtomicReference<>();
        AtomicReference<Method> observedResolvedMethod = new AtomicReference<>();
        AtomicReference<Object> observedTargetInstance = new AtomicReference<>();
        AtomicReference<String> observedTargetVersion = new AtomicReference<>();
        AtomicReference<RoutableTarget> observedRuntime = new AtomicReference<>();
        AtomicReference<Object> observedPrincipal = new AtomicReference<>();
        AtomicBoolean observedPreResolved = new AtomicBoolean(false);

        when(pipelineEngine.invoke(any(InvocationContext.class))).thenAnswer(invocation -> {
            InvocationContext ctx = invocation.getArgument(0);
            observedMethodName.set(ctx.getMethodName());
            observedTargetClass.set(ctx.resolution().getTargetClassName());
            observedResolvedMethod.set(ctx.resolution().getResolvedMethod());
            observedTargetInstance.set(ctx.routing().getTargetInstance());
            observedTargetVersion.set(ctx.getTargetVersion());
            observedRuntime.set(ctx.getRuntime());
            observedPrincipal.set(ctx.getMetadata().get(AuditMetadataKeys.PRINCIPAL));
            observedPreResolved.set(ctx.routing().isPreResolved());
            return null;
        });

        request.setUserPrincipal(() -> "alice");
        filter.doFilterInternal(request, response, filterChain);

        assertEquals("detail", observedMethodName.get());
        assertEquals(DemoController.class.getName(), observedTargetClass.get());
        assertSame(targetMethod, observedResolvedMethod.get());
        assertSame(targetInstance, observedTargetInstance.get());
        assertEquals("v1", observedTargetVersion.get());
        assertSame(runtime, observedRuntime.get());
        assertEquals("alice", observedPrincipal.get());
        assertTrue(observedPreResolved.get());
        assertSame(resolution, request.getAttribute(WebRequestKeys.ROUTE_RESOLUTION));
        assertSame(metadata, request.getAttribute(WebRequestKeys.METADATA));
        assertEquals("v1", request.getAttribute(WebRequestKeys.TARGET_VERSION));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("治理关闭时应放过非灵元请求")
    void shouldBypassNonLingRequestWhenGovernanceDisabled() throws Exception {
        LingFrameProperties properties = new LingFrameProperties();
        properties.getLingCoreGovernance().setEnabled(false);
        LingWebGovernanceFilter filter = new LingWebGovernanceFilter(
                webRouteResolver, pipelineEngine, properties, requestMappingHandlerMapping, null,
                invocationGovernanceResolver);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/lingcore/demo/detail");
        MockHttpServletResponse response = new MockHttpServletResponse();
        DemoController controller = new DemoController();
        Method targetMethod = DemoController.class.getMethod("detail");

        when(webRouteResolver.resolveRoute(any())).thenReturn(null);
        when(requestMappingHandlerMapping.getHandler(request))
                .thenReturn(new HandlerExecutionChain(
                        new HandlerMethod(controller, targetMethod)));

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(pipelineEngine, never()).invoke(any(InvocationContext.class));
    }

    @Test
    @DisplayName("灵元路由没有就绪目标实例时应返回 503")
    void shouldReturn503WhenLingRouteHasNoReadyTargetInstance() throws Exception {
        LingFrameProperties properties = new LingFrameProperties();
        properties.getLingCoreGovernance().setEnabled(true);
        LingWebGovernanceFilter filter = new LingWebGovernanceFilter(
                webRouteResolver, pipelineEngine, properties, requestMappingHandlerMapping, null,
                invocationGovernanceResolver);

        DemoController controller = new DemoController();
        Method targetMethod = DemoController.class.getMethod("detail");
        WebInterfaceMetadata metadata = WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .version("v2")
                .targetBean(controller)
                .targetMethod(targetMethod)
                .classLoader(DemoController.class.getClassLoader())
                .urlPattern("/ling-a/demo/detail")
                .httpMethod("GET")
                .requiredPermission("demo:read")
                .build();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ling-a/demo/detail");
        MockHttpServletResponse response = new MockHttpServletResponse();
        WebRouteResolution resolution = new WebRouteResolution(
                "GET#/ling-a/demo/detail", metadata, runtime, null);

        when(webRouteResolver.resolveRoute(any())).thenReturn(resolution);

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(503, response.getStatus());
        verify(pipelineEngine, never()).invoke(any(InvocationContext.class));
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("灵元路由目标方法无法继续解析时应返回 503")
    void shouldReturn503WhenLingRouteTargetMethodIsUnavailable() throws Exception {
        LingFrameProperties properties = new LingFrameProperties();
        properties.getLingCoreGovernance().setEnabled(true);
        LingWebGovernanceFilter filter = new LingWebGovernanceFilter(
                webRouteResolver, pipelineEngine, properties, requestMappingHandlerMapping, null,
                invocationGovernanceResolver);

        WebInterfaceMetadata metadata = WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .version("v1")
                .targetClassName(DemoController.class.getName())
                .targetMethodName("missing")
                .targetMethodParameterTypeNames(new String[0])
                .classLoader(DemoController.class.getClassLoader())
                .urlPattern("/ling-a/demo/detail")
                .httpMethod("GET")
                .requiredPermission("demo:read")
                .build();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ling-a/demo/detail");
        MockHttpServletResponse response = new MockHttpServletResponse();
        WebRouteResolution resolution = new WebRouteResolution(
                "GET#/ling-a/demo/detail", metadata, runtime, targetInstance);

        when(webRouteResolver.resolveRoute(any())).thenReturn(resolution);

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(503, response.getStatus());
        assertNotNull(response.getErrorMessage());
        verify(pipelineEngine, never()).invoke(any(InvocationContext.class));
        verify(filterChain, never()).doFilter(request, response);
    }

    static class DemoController {
        public String detail() {
            return "ok";
        }
    }
}