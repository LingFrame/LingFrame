package com.lingframe.starter.filter;

import com.lingframe.api.security.AuditMetadataKeys;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.metrics.LingHealthMetrics;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.spi.RoutableTarget;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.governance.EntryInvocationGovernanceResolver;
import com.lingframe.starter.web.WebInterfaceManager;
import com.lingframe.starter.web.WebInterfaceMetadata;
import com.lingframe.starter.web.WebRequestFacade;
import com.lingframe.starter.web.WebRouteResolution;
import com.lingframe.starter.web.WebRouteResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.servlet.FilterChain;
import java.lang.reflect.Method;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LingWebGovernanceFilter 测试")
class LingWebGovernanceFilterTest {

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
    @Mock
    private MetricsCollector metricsCollector;
    @Mock
    private LingHealthMetrics healthMetrics;

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
            request.setAttribute(WebInterfaceManager.REQUEST_ROUTE_RESOLUTION_KEY, resolution);
            request.setAttribute(WebInterfaceManager.REQUEST_METADATA_KEY, metadata);
            request.setAttribute(WebInterfaceManager.REQUEST_TARGET_VERSION_KEY, metadata.getVersion());
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
        verify(filterChain).doFilter(request, response);
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

    @Test
    @DisplayName("非灵元请求且核心治理未启用时应直接放行")
    void testNonLingRequestCoreGovernanceDisabled() throws Exception {
        LingFrameProperties properties = new LingFrameProperties();
        properties.getLingCoreGovernance().setEnabled(false); // 禁用核心治理
        
        LingWebGovernanceFilter filter = new LingWebGovernanceFilter(
                webRouteResolver, pipelineEngine, properties, requestMappingHandlerMapping, null,
                invocationGovernanceResolver);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/host/api");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(webRouteResolver.resolveRoute(any())).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(pipelineEngine, never()).invoke(any());
    }

    @Test
    @DisplayName("非灵元请求且无法解析出 HandlerMethod 时应直接放行")
    void testNonLingRequestNoHandlerMethod() throws Exception {
        LingFrameProperties properties = new LingFrameProperties();
        properties.getLingCoreGovernance().setEnabled(true);
        
        LingWebGovernanceFilter filter = new LingWebGovernanceFilter(
                webRouteResolver, pipelineEngine, properties, requestMappingHandlerMapping, null,
                invocationGovernanceResolver);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/host/api");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(webRouteResolver.resolveRoute(any())).thenReturn(null);
        when(requestMappingHandlerMapping.getHandler(any())).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(pipelineEngine, never()).invoke(any());
    }

    @Test
    @DisplayName("灵元请求且启用治理应走治理流程并记录 Metrics")
    void testLingRequestWithMetrics() throws Exception {
        LingFrameProperties properties = new LingFrameProperties();
        properties.getLingCoreGovernance().setEnabled(true);
        
        ObjectProvider<MetricsCollector> metricsProvider = mock(ObjectProvider.class);
        when(metricsProvider.getIfAvailable()).thenReturn(metricsCollector);
        when(metricsCollector.getOrCreate(anyString())).thenReturn(healthMetrics);
        when(metricsCollector.getOrCreate(anyString(), any())).thenReturn(healthMetrics);

        LingWebGovernanceFilter filter = new LingWebGovernanceFilter(
                webRouteResolver, pipelineEngine, properties, requestMappingHandlerMapping, metricsProvider,
                invocationGovernanceResolver);

        DemoController controller = new DemoController();
        Method expectedMethod = DemoController.class.getMethod("detail");
        WebInterfaceMetadata metadata = WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .version("v1")
                .targetBean(controller)
                .targetMethod(expectedMethod)
                .classLoader(DemoController.class.getClassLoader())
                .build();
        WebRouteResolution resolution = new WebRouteResolution("GET#/detail", metadata, runtime, targetInstance);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/detail");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(webRouteResolver.resolveRoute(any())).thenReturn(resolution);
        when(targetInstance.getVersion()).thenReturn("v1");

        filter.doFilterInternal(request, response, filterChain);

        verify(pipelineEngine).invoke(any(InvocationContext.class));
        verify(filterChain).doFilter(request, response);
        verify(healthMetrics).recordSuccess(anyLong());
    }

    @Test
    @DisplayName("治理失败拒绝时应返回正确的 HTTP 状态码")
    void testGovernanceFailureStatusCodes() throws Exception {
        LingFrameProperties properties = new LingFrameProperties();
        properties.getLingCoreGovernance().setEnabled(true);

        LingWebGovernanceFilter filter = new LingWebGovernanceFilter(
                webRouteResolver, pipelineEngine, properties, requestMappingHandlerMapping, null,
                invocationGovernanceResolver);

        DemoController controller = new DemoController();
        Method targetMethod = DemoController.class.getMethod("detail");
        WebInterfaceMetadata metadata = WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .targetBean(controller)
                .targetMethod(targetMethod)
                .classLoader(DemoController.class.getClassLoader())
                .build();
        WebRouteResolution resolution = new WebRouteResolution("GET#/detail", metadata, runtime, targetInstance);

        // 1. 安全校验异常 (SECURITY_REJECTED) -> 403
        {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/detail");
            MockHttpServletResponse response = new MockHttpServletResponse();
            when(webRouteResolver.resolveRoute(any())).thenReturn(resolution);
            when(targetInstance.getVersion()).thenReturn("v1");

            doThrow(new com.lingframe.api.exception.LingInvocationException(
                    "ling-a:http",
                    com.lingframe.api.exception.LingInvocationException.ErrorKind.SECURITY_REJECTED,
                    "Access denied"
            )).when(pipelineEngine).invoke(any());

            filter.doFilterInternal(request, response, filterChain);
            assertEquals(403, response.getStatus());
        }

        // 2. 状态异常 (STATE_REJECTED) -> 503
        {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/detail");
            MockHttpServletResponse response = new MockHttpServletResponse();
            when(webRouteResolver.resolveRoute(any())).thenReturn(resolution);

            doThrow(new com.lingframe.api.exception.LingInvocationException(
                    "ling-a:http",
                    com.lingframe.api.exception.LingInvocationException.ErrorKind.STATE_REJECTED,
                    "Ling is disabled"
            )).when(pipelineEngine).invoke(any());

            filter.doFilterInternal(request, response, filterChain);
            assertEquals(503, response.getStatus());
        }

        // 3. 其它未知异常 -> 500
        {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/detail");
            MockHttpServletResponse response = new MockHttpServletResponse();
            when(webRouteResolver.resolveRoute(any())).thenReturn(resolution);

            doThrow(new com.lingframe.api.exception.LingInvocationException(
                    "ling-a:http",
                    com.lingframe.api.exception.LingInvocationException.ErrorKind.RATE_LIMITED,
                    "Unexpected server error"
            )).when(pipelineEngine).invoke(any());

            filter.doFilterInternal(request, response, filterChain);
            assertEquals(500, response.getStatus());
        }
    }

    @Test
    @DisplayName("下游业务逻辑抛出异常或超时应记录相应的 Metrics")
    void testMetricsWithFailureAndTimeout() throws Exception {
        LingFrameProperties properties = new LingFrameProperties();
        properties.getLingCoreGovernance().setEnabled(true);

        ObjectProvider<MetricsCollector> metricsProvider = mock(ObjectProvider.class);
        when(metricsProvider.getIfAvailable()).thenReturn(metricsCollector);
        when(metricsCollector.getOrCreate(anyString())).thenReturn(healthMetrics);
        when(metricsCollector.getOrCreate(anyString(), any())).thenReturn(healthMetrics);

        LingWebGovernanceFilter filter = new LingWebGovernanceFilter(
                webRouteResolver, pipelineEngine, properties, requestMappingHandlerMapping, metricsProvider,
                invocationGovernanceResolver);

        DemoController controller = new DemoController();
        Method targetMethod = DemoController.class.getMethod("detail");
        WebInterfaceMetadata metadata = WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .targetBean(controller)
                .targetMethod(targetMethod)
                .classLoader(DemoController.class.getClassLoader())
                .build();
        WebRouteResolution resolution = new WebRouteResolution("GET#/detail", metadata, runtime, targetInstance);

        // 1. 下游普通异常 (Exception)
        {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/detail");
            MockHttpServletResponse response = new MockHttpServletResponse();
            when(webRouteResolver.resolveRoute(any())).thenReturn(resolution);
            when(targetInstance.getVersion()).thenReturn("v1");
            
            // 模拟 filterChain 抛出 RuntimeException
            Answer<Void> throwAnswer = inv -> {
                throw new RuntimeException("Biz Error");
            };
            doAnswer(throwAnswer).when(filterChain).doFilter(any(), any());
            doReturn(null).when(pipelineEngine).invoke(any());

            try {
                filter.doFilterInternal(request, response, filterChain);
            } catch (Exception e) {
                // Expected
            }
            verify(healthMetrics).recordFailure(anyLong(), eq(false));
        }

        // 2. 下游超时异常 (TimeoutException)
        {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/detail");
            MockHttpServletResponse response = new MockHttpServletResponse();
            when(webRouteResolver.resolveRoute(any())).thenReturn(resolution);

            Answer<Void> throwTimeout = inv -> {
                throw new TimeoutException("Read timed out");
            };
            doAnswer(throwTimeout).when(filterChain).doFilter(any(), any());

            try {
                filter.doFilterInternal(request, response, filterChain);
            } catch (Exception e) {
                // Expected
            }
            verify(healthMetrics).recordFailure(anyLong(), eq(true));
        }
    }

    static class DemoController {
        public String detail() {
            return "ok";
        }
    }
}
