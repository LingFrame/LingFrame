package com.lingframe.starter.web;

import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.spi.TrafficRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultWebRouteResolver 测试")
class DefaultWebRouteResolverTest {

    @Mock
    private LingRepository lingRepository;
    @Mock
    private TrafficRouter trafficRouter;
    @Mock
    private LingRuntime runtime;
    @Mock
    private LingInstance v1Instance;
    @Mock
    private LingInstance v2Instance;

    @Test
    @DisplayName("治理与分发阶段应共享同一个已解析路由")
    void shouldShareResolvedRouteBetweenGovernanceAndDispatch() throws Exception {
        Map<String, List<WebInterfaceMetadata>> metadataMap = new ConcurrentHashMap<>();
        Map<String, Set<String>> routePatternsByMethod = new ConcurrentHashMap<>();
        Method targetMethod = DemoController.class.getMethod("detail");
        WebInterfaceMetadata v1Meta = metadata("ling-a", "v1", targetMethod);
        WebInterfaceMetadata v2Meta = metadata("ling-a", "v2", targetMethod);
        String routeKey = "GET#/ling-a/demo/detail";
        metadataMap.put(routeKey, Arrays.asList(v1Meta, v2Meta));
        routePatternsByMethod.put("GET", Collections.singleton("/ling-a/demo/detail"));

        DefaultWebRouteResolver resolver = new DefaultWebRouteResolver(
                metadataMap, routePatternsByMethod, lingRepository, trafficRouter);
        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);
        when(runtime.getReadyInstances()).thenReturn(Arrays.asList(v1Instance, v2Instance));
        when(v1Instance.getVersion()).thenReturn("v1");
        when(v2Instance.getVersion()).thenReturn("v2");
        when(trafficRouter.route(anyList(), any(InvocationContext.class))).thenAnswer(invocation -> {
            InvocationContext ctx = invocation.getArgument(1);
            assertEquals("ling-a", ctx.getTargetLingId());
            assertEquals("ling-a:http", ctx.getServiceFQSID());
            assertSame(runtime, ctx.getRuntime());
            return v2Instance;
        });

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ling-a/demo/detail");
        WebRouteResolution first = resolver.resolveRoute((request));
        WebRouteResolution second = resolver.resolveRoute(routeKey, (request));
        WebInterfaceMetadata requestMetadata = first.getMetadata();

        assertSame(first, second);
        assertEquals(routeKey, first.getRouteKey());
        assertEquals(v2Meta, requestMetadata);
        assertSame(runtime, first.getRuntime());
        assertSame(v2Instance, first.getTargetInstance());
        assertSame(first, request.getAttribute(WebInterfaceManager.REQUEST_ROUTE_RESOLUTION_KEY));
        assertSame(requestMetadata, request.getAttribute(WebInterfaceManager.REQUEST_METADATA_KEY));
        assertEquals("v2", request.getAttribute(WebInterfaceManager.REQUEST_TARGET_VERSION_KEY));
        verify(trafficRouter).route(anyList(), any(InvocationContext.class));
    }

    @Test
    @DisplayName("应根据请求路径匹配模板路由")
    void shouldResolveTemplatedRouteFromRequest() throws Exception {
        Map<String, List<WebInterfaceMetadata>> metadataMap = new ConcurrentHashMap<>();
        Map<String, Set<String>> routePatternsByMethod = new ConcurrentHashMap<>();
        Method targetMethod = DemoController.class.getMethod("detail");
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
        metadataMap.put("GET#/ling-a/demo/{id}", Collections.singletonList(metadata));
        routePatternsByMethod.put("GET", Collections.singleton("/ling-a/demo/{id}"));

        DefaultWebRouteResolver resolver = new DefaultWebRouteResolver(
                metadataMap, routePatternsByMethod, lingRepository, trafficRouter);
        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);
        when(runtime.getReadyInstances()).thenReturn(Collections.singletonList(v1Instance));
        when(v1Instance.getVersion()).thenReturn("v1");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ling-a/demo/42");
        WebRouteResolution resolution = resolver.resolveRoute((request));

        assertNotNull(resolution);
        assertEquals("ling-a", resolution.getMetadata().getLingId());
        assertEquals("v1", resolution.getMetadata().getVersion());
        assertEquals("/ling-a/demo/{id}", resolution.getMetadata().getUrlPattern());
        assertEquals("GET", resolution.getMetadata().getHttpMethod());
        assertSame(targetMethod, resolution.getMetadata().getTargetMethod());
        assertEquals("GET#/ling-a/demo/{id}", resolution.getRouteKey());
        assertEquals("v1", request.getAttribute(WebInterfaceManager.REQUEST_TARGET_VERSION_KEY));
        assertSame(resolution.getMetadata(), request.getAttribute(WebInterfaceManager.REQUEST_METADATA_KEY));
    }

    @Test
    @DisplayName("流量路由前应优先使用请求强制指定的版本")
    void shouldPreferForcedRequestVersionBeforeRouting() throws Exception {
        Map<String, List<WebInterfaceMetadata>> metadataMap = new ConcurrentHashMap<>();
        Map<String, Set<String>> routePatternsByMethod = new ConcurrentHashMap<>();
        Method targetMethod = DemoController.class.getMethod("detail");
        WebInterfaceMetadata v1Meta = metadata("ling-a", "v1", targetMethod);
        WebInterfaceMetadata v2Meta = metadata("ling-a", "v2", targetMethod);
        String routeKey = "GET#/ling-a/demo/detail";
        metadataMap.put(routeKey, Arrays.asList(v1Meta, v2Meta));
        routePatternsByMethod.put("GET", Collections.singleton("/ling-a/demo/detail"));

        DefaultWebRouteResolver resolver = new DefaultWebRouteResolver(
                metadataMap, routePatternsByMethod, lingRepository, trafficRouter);
        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);
        when(runtime.getReadyInstances()).thenReturn(Arrays.asList(v1Instance, v2Instance));
        when(v1Instance.getVersion()).thenReturn("v1");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ling-a/demo/detail");
        request.setAttribute(WebInterfaceManager.REQUEST_TARGET_VERSION_KEY, "v1");

        WebRouteResolution resolution = resolver.resolveRoute((request));

        assertNotNull(resolution);
        assertEquals(v1Meta, resolution.getMetadata());
        assertSame(v1Instance, resolution.getTargetInstance());
        verify(trafficRouter, never()).route(anyList(), any(InvocationContext.class));
    }

    @Test
    @DisplayName("路由器返回 null 时应回退到就绪元数据")
    void shouldFallbackToReadyMetadataWhenRouterReturnsNull() throws Exception {
        Map<String, List<WebInterfaceMetadata>> metadataMap = new ConcurrentHashMap<>();
        Map<String, Set<String>> routePatternsByMethod = new ConcurrentHashMap<>();
        Method targetMethod = DemoController.class.getMethod("detail");
        WebInterfaceMetadata v1Meta = metadata("ling-a", "v1", targetMethod);
        WebInterfaceMetadata v2Meta = metadata("ling-a", "v2", targetMethod);
        String routeKey = "GET#/ling-a/demo/detail";
        metadataMap.put(routeKey, Arrays.asList(v1Meta, v2Meta));
        routePatternsByMethod.put("GET", Collections.singleton("/ling-a/demo/detail"));

        DefaultWebRouteResolver resolver = new DefaultWebRouteResolver(
                metadataMap, routePatternsByMethod, lingRepository, trafficRouter);
        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);
        when(runtime.getReadyInstances()).thenReturn(Collections.singletonList(v2Instance));
        when(v2Instance.getVersion()).thenReturn("v2");
        when(trafficRouter.route(anyList(), any(InvocationContext.class))).thenReturn(null);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ling-a/demo/detail");
        WebRouteResolution resolution = resolver.resolveRoute(routeKey, (request));

        assertNotNull(resolution);
        assertEquals(v2Meta, resolution.getMetadata());
        assertSame(v2Instance, resolution.getTargetInstance());
        verify(trafficRouter).route(anyList(), any(InvocationContext.class));
    }

    @Test
    @DisplayName("请求携带 context path 时应匹配模板路由")
    void shouldResolveTemplatedRouteWithContextPath() throws Exception {
        Map<String, List<WebInterfaceMetadata>> metadataMap = new ConcurrentHashMap<>();
        Map<String, Set<String>> routePatternsByMethod = new ConcurrentHashMap<>();
        Method targetMethod = DemoController.class.getMethod("detail");
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
        metadataMap.put("GET#/ling-a/demo/{id}", Collections.singletonList(metadata));
        routePatternsByMethod.put("GET", Collections.singleton("/ling-a/demo/{id}"));

        DefaultWebRouteResolver resolver = new DefaultWebRouteResolver(
                metadataMap, routePatternsByMethod, lingRepository, trafficRouter);
        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);
        when(runtime.getReadyInstances()).thenReturn(Collections.singletonList(v1Instance));
        when(v1Instance.getVersion()).thenReturn("v1");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/ling-a/demo/42");
        request.setContextPath("/app");

        WebRouteResolution resolution = resolver.resolveRoute((request));

        assertNotNull(resolution);
        assertEquals("ling-a", resolution.getMetadata().getLingId());
        assertEquals("v1", resolution.getMetadata().getVersion());
        assertEquals("/ling-a/demo/{id}", resolution.getMetadata().getUrlPattern());
        assertEquals("GET", resolution.getMetadata().getHttpMethod());
        assertSame(targetMethod, resolution.getMetadata().getTargetMethod());
        assertEquals("GET#/ling-a/demo/{id}", resolution.getRouteKey());
    }

    @Test
    @DisplayName("请求携带 context 与 servlet path 时应匹配模板路由")
    void shouldResolveTemplatedRouteWithContextAndServletPath() throws Exception {
        Map<String, List<WebInterfaceMetadata>> metadataMap = new ConcurrentHashMap<>();
        Map<String, Set<String>> routePatternsByMethod = new ConcurrentHashMap<>();
        Method targetMethod = DemoController.class.getMethod("detail");
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
        metadataMap.put("GET#/ling-a/demo/{id}", Collections.singletonList(metadata));
        routePatternsByMethod.put("GET", Collections.singleton("/ling-a/demo/{id}"));

        DefaultWebRouteResolver resolver = new DefaultWebRouteResolver(
                metadataMap, routePatternsByMethod, lingRepository, trafficRouter);
        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);
        when(runtime.getReadyInstances()).thenReturn(Collections.singletonList(v1Instance));
        when(v1Instance.getVersion()).thenReturn("v1");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/gateway/ling-a/demo/42");
        request.setContextPath("/app");
        request.setServletPath("/gateway");

        WebRouteResolution resolution = resolver.resolveRoute((request));

        assertNotNull(resolution);
        assertEquals("ling-a", resolution.getMetadata().getLingId());
        assertEquals("v1", resolution.getMetadata().getVersion());
        assertEquals("/ling-a/demo/{id}", resolution.getMetadata().getUrlPattern());
        assertEquals("GET", resolution.getMetadata().getHttpMethod());
        assertSame(targetMethod, resolution.getMetadata().getTargetMethod());
        assertEquals("GET#/ling-a/demo/{id}", resolution.getRouteKey());
    }

    @Test
    @DisplayName("转发头在配置白名单内时应匹配模板路由（C10 显式信任）")
    void shouldResolveTemplatedRouteWithForwardedPrefix() throws Exception {
        Map<String, List<WebInterfaceMetadata>> metadataMap = new ConcurrentHashMap<>();
        Map<String, Set<String>> routePatternsByMethod = new ConcurrentHashMap<>();
        Method targetMethod = DemoController.class.getMethod("detail");
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
        metadataMap.put("GET#/ling-a/demo/{id}", Collections.singletonList(metadata));
        routePatternsByMethod.put("GET", Collections.singleton("/ling-a/demo/{id}"));

        DefaultWebRouteResolver resolver = new DefaultWebRouteResolver(
                metadataMap, routePatternsByMethod, lingRepository, trafficRouter,
                Collections.singletonList("/proxy"));
        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);
        when(runtime.getReadyInstances()).thenReturn(Collections.singletonList(v1Instance));
        when(v1Instance.getVersion()).thenReturn("v1");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/proxy/ling-a/demo/42");
        request.addHeader("X-Forwarded-Prefix", "/proxy");

        WebRouteResolution resolution = resolver.resolveRoute((request));

        assertNotNull(resolution);
        assertEquals("ling-a", resolution.getMetadata().getLingId());
        assertEquals("/ling-a/demo/{id}", resolution.getMetadata().getUrlPattern());
        assertEquals("GET#/ling-a/demo/{id}", resolution.getRouteKey());
    }

    @Test
    @DisplayName("不持有强 Bean 引用时应匹配 SpringDoc bean-name 处理器")
    void shouldResolveSpringDocHandlerWithoutStrongReferences() throws Exception {
        Map<String, List<WebInterfaceMetadata>> metadataMap = new ConcurrentHashMap<>();
        Map<String, Set<String>> routePatternsByMethod = new ConcurrentHashMap<>();
        Method targetMethod = DemoController.class.getMethod("detail");
        String routeKey = "GET#/ling-a/demo/detail";
        String springDocBeanName = "ling-a:v1:" + DemoController.class.getName();
        WebInterfaceMetadata metadata = WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .version("v1")
                .targetClassName(DemoController.class.getName())
                .targetMethodName(targetMethod.getName())
                .targetMethodParameterTypeNames(new String[0])
                .springDocBeanName(springDocBeanName)
                .urlPattern("/ling-a/demo/detail")
                .httpMethod("GET")
                .requiredPermission("demo:read")
                .build();
        metadataMap.put(routeKey, Collections.singletonList(metadata));
        routePatternsByMethod.put("GET", Collections.singleton("/ling-a/demo/detail"));

        DefaultWebRouteResolver resolver = new DefaultWebRouteResolver(
                metadataMap, routePatternsByMethod, lingRepository, trafficRouter);
        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);

        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton(springDocBeanName, new DemoController());
        HandlerMethod handlerMethod = new HandlerMethod(springDocBeanName, beanFactory, targetMethod);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ling-a/demo/detail");

        WebRouteResolution resolution = resolver.resolveRoute((request), handlerMethod);

        assertNotNull(resolution);
        assertEquals(metadata, resolution.getMetadata());
        assertEquals(routeKey, resolution.getRouteKey());
        assertSame(resolution, request.getAttribute(WebInterfaceManager.REQUEST_ROUTE_RESOLUTION_KEY));
        assertSame(resolution.getMetadata(), request.getAttribute(WebInterfaceManager.REQUEST_METADATA_KEY));
        assertEquals("v1", request.getAttribute(WebInterfaceManager.REQUEST_TARGET_VERSION_KEY));
    }

    @Test
    @DisplayName("应根据参数类型匹配 SpringDoc 重载处理器")
    void shouldResolveSpringDocOverloadedHandlerByParameterTypes() throws Exception {
        Map<String, List<WebInterfaceMetadata>> metadataMap = new ConcurrentHashMap<>();
        Map<String, Set<String>> routePatternsByMethod = new ConcurrentHashMap<>();
        Method stringMethod = DemoController.class.getMethod("overloaded", String.class);
        Method integerMethod = DemoController.class.getMethod("overloaded", Integer.class);
        String routeKey = "GET#/ling-a/demo/overloaded";
        String springDocBeanName = "ling-a:v1:" + DemoController.class.getName();

        WebInterfaceMetadata stringMetadata = WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .version("v1")
                .targetClassName(DemoController.class.getName())
                .targetMethodName(stringMethod.getName())
                .targetMethodParameterTypeNames(new String[] {String.class.getName()})
                .springDocBeanName(springDocBeanName)
                .classLoader(DemoController.class.getClassLoader())
                .urlPattern("/ling-a/demo/overloaded")
                .httpMethod("GET")
                .requiredPermission("demo:read")
                .build();
        WebInterfaceMetadata integerMetadata = WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .version("v1")
                .targetClassName(DemoController.class.getName())
                .targetMethodName(integerMethod.getName())
                .targetMethodParameterTypeNames(new String[] {Integer.class.getName()})
                .springDocBeanName(springDocBeanName)
                .classLoader(DemoController.class.getClassLoader())
                .urlPattern("/ling-a/demo/overloaded")
                .httpMethod("GET")
                .requiredPermission("demo:read")
                .build();
        metadataMap.put(routeKey, Arrays.asList(stringMetadata, integerMetadata));
        routePatternsByMethod.put("GET", Collections.singleton("/ling-a/demo/overloaded"));

        DefaultWebRouteResolver resolver = new DefaultWebRouteResolver(
                metadataMap, routePatternsByMethod, lingRepository, trafficRouter);
        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);

        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton(springDocBeanName, new DemoController());
        HandlerMethod handlerMethod = new HandlerMethod(springDocBeanName, beanFactory, integerMethod);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ling-a/demo/overloaded");

        WebRouteResolution resolution = resolver.resolveRoute((request), handlerMethod);

        assertNotNull(resolution);
        assertEquals(integerMetadata, resolution.getMetadata());
        assertArrayEquals(new String[] {Integer.class.getName()},
                resolution.getMetadata().getTargetMethodParameterTypeNames());
        assertEquals(integerMethod, resolution.getMetadata().getTargetMethod());
        assertEquals(routeKey, resolution.getRouteKey());
    }

    @Test
    @DisplayName("应匹配带 params 与 headers 条件的路由")
    void shouldResolveRouteWithParamsAndHeadersConditions() throws Exception {
        Map<String, List<WebInterfaceMetadata>> metadataMap = new ConcurrentHashMap<>();
        Map<String, Set<String>> routePatternsByMethod = new ConcurrentHashMap<>();
        Method fullMethod = DemoController.class.getMethod("full");
        Method liteMethod = DemoController.class.getMethod("lite");

        WebInterfaceMetadata fullMeta = conditionedMetadata(
                fullMethod, "GET", "/ling-a/demo/detail",
                new String[] {"mode=full"},
                new String[] {"X-Test=1"},
                new String[0],
                new String[0]);
        WebInterfaceMetadata liteMeta = conditionedMetadata(
                liteMethod, "GET", "/ling-a/demo/detail",
                new String[] {"mode=lite"},
                new String[0],
                new String[0],
                new String[0]);
        metadataMap.put(fullMeta.buildRouteKey(), Collections.singletonList(fullMeta));
        metadataMap.put(liteMeta.buildRouteKey(), Collections.singletonList(liteMeta));
        routePatternsByMethod.put("GET", Collections.singleton("/ling-a/demo/detail"));

        DefaultWebRouteResolver resolver = new DefaultWebRouteResolver(
                metadataMap, routePatternsByMethod, lingRepository, trafficRouter);
        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);
        when(runtime.getReadyInstances()).thenReturn(Collections.singletonList(v1Instance));
        when(v1Instance.getVersion()).thenReturn("v1");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ling-a/demo/detail");
        request.addParameter("mode", "full");
        request.addHeader("X-Test", "1");

        WebRouteResolution resolution = resolver.resolveRoute((request));

        assertNotNull(resolution);
        assertEquals("full", resolution.getMetadata().getTargetMethodName());
        assertArrayEquals(new String[] {"mode=full"}, resolution.getMetadata().getParams());
        assertArrayEquals(new String[] {"X-Test=1"}, resolution.getMetadata().getHeaders());

        MockHttpServletRequest missingHeader = new MockHttpServletRequest("GET", "/ling-a/demo/detail");
        missingHeader.addParameter("mode", "full");
        assertNull(resolver.resolveRoute((missingHeader)));
    }

    @Test
    @DisplayName("应匹配带 consumes 与 produces 条件的路由")
    void shouldResolveRouteWithConsumesAndProducesConditions() throws Exception {
        Map<String, List<WebInterfaceMetadata>> metadataMap = new ConcurrentHashMap<>();
        Map<String, Set<String>> routePatternsByMethod = new ConcurrentHashMap<>();
        Method jsonMethod = DemoController.class.getMethod("createJson");
        Method textMethod = DemoController.class.getMethod("createText");

        WebInterfaceMetadata jsonMeta = conditionedMetadata(
                jsonMethod, "POST", "/ling-a/demo/detail",
                new String[0],
                new String[0],
                new String[] {"application/json"},
                new String[] {"application/json"});
        WebInterfaceMetadata textMeta = conditionedMetadata(
                textMethod, "POST", "/ling-a/demo/detail",
                new String[0],
                new String[0],
                new String[] {"text/plain"},
                new String[] {"text/plain"});
        metadataMap.put(jsonMeta.buildRouteKey(), Collections.singletonList(jsonMeta));
        metadataMap.put(textMeta.buildRouteKey(), Collections.singletonList(textMeta));
        routePatternsByMethod.put("POST", Collections.singleton("/ling-a/demo/detail"));

        DefaultWebRouteResolver resolver = new DefaultWebRouteResolver(
                metadataMap, routePatternsByMethod, lingRepository, trafficRouter);
        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);
        when(runtime.getReadyInstances()).thenReturn(Collections.singletonList(v1Instance));
        when(v1Instance.getVersion()).thenReturn("v1");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ling-a/demo/detail");
        request.setContentType("application/json");
        request.addHeader("Accept", "application/json");

        WebRouteResolution resolution = resolver.resolveRoute((request));

        assertNotNull(resolution);
        assertEquals("createJson", resolution.getMetadata().getTargetMethodName());
        assertArrayEquals(new String[] {"application/json"}, resolution.getMetadata().getConsumes());
        assertArrayEquals(new String[] {"application/json"}, resolution.getMetadata().getProduces());

        MockHttpServletRequest wrongContentType = new MockHttpServletRequest("POST", "/ling-a/demo/detail");
        wrongContentType.setContentType("application/xml");
        wrongContentType.addHeader("Accept", "application/json");
        assertNull(resolver.resolveRoute((wrongContentType)));
    }

    @Test
    @DisplayName("源元数据被清理后请求解析结果仍应保持稳定")
    void shouldKeepRequestResolutionStableAfterSourceMetadataCleared() throws Exception {
        Map<String, List<WebInterfaceMetadata>> metadataMap = new ConcurrentHashMap<>();
        Map<String, Set<String>> routePatternsByMethod = new ConcurrentHashMap<>();
        DemoController controller = new DemoController();
        Method targetMethod = DemoController.class.getMethod("detail");
        WebInterfaceMetadata sourceMetadata = WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .version("v1")
                .targetBeanName("demoController")
                .targetBean(controller)
                .targetClassName(DemoController.class.getName())
                .targetMethodName(targetMethod.getName())
                .targetMethodParameterTypeNames(new String[0])
                .targetMethod(targetMethod)
                .classLoader(DemoController.class.getClassLoader())
                .urlPattern("/ling-a/demo/detail")
                .httpMethod("GET")
                .requiredPermission("demo:read")
                .build();
        sourceMetadata.minimizeCoreStrongReferences();
        String routeKey = "GET#/ling-a/demo/detail";
        metadataMap.put(routeKey, Collections.singletonList(sourceMetadata));
        routePatternsByMethod.put("GET", Collections.singleton("/ling-a/demo/detail"));

        DefaultWebRouteResolver resolver = new DefaultWebRouteResolver(
                metadataMap, routePatternsByMethod, lingRepository, trafficRouter);
        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);
        when(runtime.getReadyInstances()).thenReturn(Collections.singletonList(v1Instance));
        when(v1Instance.getVersion()).thenReturn("v1");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ling-a/demo/detail");
        WebRouteResolution resolution = resolver.resolveRoute((request));
        WebInterfaceMetadata requestMetadata = resolution.getMetadata();
        sourceMetadata.clearReferences();

        assertNotNull(resolution);
        assertNotSame(sourceMetadata, requestMetadata);
        assertSame(controller, requestMetadata.getTargetBean());
        assertSame(targetMethod, requestMetadata.getTargetMethod());
        assertSame(DemoController.class.getClassLoader(), requestMetadata.getClassLoader());
        assertEquals("v1", requestMetadata.getVersion());
    }

    private WebInterfaceMetadata metadata(String lingId, String version, Method targetMethod) {
        return WebInterfaceMetadata.builder()
                .lingId(lingId)
                .version(version)
                .targetBeanName("demoController")
                .targetBean(new DemoController())
                .targetClassName(DemoController.class.getName())
                .targetMethodName(targetMethod.getName())
                .targetMethodParameterTypeNames(new String[0])
                .targetMethod(targetMethod)
                .classLoader(DemoController.class.getClassLoader())
                .urlPattern("/ling-a/demo/detail")
                .httpMethod("GET")
                .requiredPermission("demo:read")
                .shouldAudit(false)
                .auditAction("DETAIL")
                .build();
    }

    private WebInterfaceMetadata conditionedMetadata(Method targetMethod,
                                                     String httpMethod,
                                                     String urlPattern,
                                                     String[] params,
                                                     String[] headers,
                                                     String[] consumes,
                                                     String[] produces) {
        RequestMappingInfo.Builder builder = RequestMappingInfo.paths(urlPattern)
                .methods(RequestMethod.valueOf(httpMethod));
        if (params.length > 0) {
            builder.params(params);
        }
        if (headers.length > 0) {
            builder.headers(headers);
        }
        if (consumes.length > 0) {
            builder.consumes(consumes);
        }
        if (produces.length > 0) {
            builder.produces(produces);
        }
        return WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .version("v1")
                .targetBeanName("demoController")
                .targetBean(new DemoController())
                .targetClassName(DemoController.class.getName())
                .targetMethodName(targetMethod.getName())
                .targetMethodParameterTypeNames(new String[0])
                .targetMethod(targetMethod)
                .classLoader(DemoController.class.getClassLoader())
                .urlPattern(urlPattern)
                .httpMethod(httpMethod)
                .params(params)
                .headers(headers)
                .consumes(consumes)
                .produces(produces)
                .requiredPermission("demo:read")
                .requestMappingInfo(builder.build())
                .build();
    }

    @Test
    @DisplayName("路由解析应归一化 . / .. / %2e 路径段（C10）")
    void shouldNormalizeDotSegmentsWhenResolving() throws Exception {
        Map<String, List<WebInterfaceMetadata>> metadataMap = new ConcurrentHashMap<>();
        Map<String, Set<String>> routePatternsByMethod = new ConcurrentHashMap<>();
        Method targetMethod = DemoController.class.getMethod("detail");
        WebInterfaceMetadata metadata = conditionedMetadata(targetMethod, "GET", "/ling-a/demo/detail", new String[0], new String[0], new String[0], new String[0]);
        String routeKey = "GET#/ling-a/demo/detail";
        metadataMap.put(routeKey, Collections.singletonList(metadata));
        routePatternsByMethod.put("GET", Collections.singleton("/ling-a/demo/detail"));

        DefaultWebRouteResolver resolver = new DefaultWebRouteResolver(
                metadataMap, routePatternsByMethod, lingRepository, trafficRouter);
        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);
        when(runtime.getReadyInstances()).thenReturn(Collections.singletonList(v1Instance));
        when(v1Instance.getVersion()).thenReturn("v1");

        // .. / . / %2e%2e / %2e 变体均应在归一化后命中同一路由，而非被路由绕过
        String[] attemptedUris = {
                "/ling-a/demo/../demo/detail",
                "/ling-a/./demo/detail",
                "/ling-a/%2e%2e/ling-a/demo/detail",
                "/ling-a/%2e/demo/detail",
                "/x/../ling-a/demo/detail",
                "//ling-a/demo/detail"
        };
        for (String uri : attemptedUris) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
            WebRouteResolution resolution = resolver.resolveRoute(request);
            assertNotNull(resolution, "URI 「" + uri + "」 应经归一化后命中路由");
        }
    }

    @Test
    @DisplayName("转发头被伪造且未配白名单时应不被采信（C10 默认安全）")
    void shouldIgnoreForwardedPrefixHeadersWithoutTrustedWhitelist() throws Exception {
        Map<String, List<WebInterfaceMetadata>> metadataMap = new ConcurrentHashMap<>();
        Map<String, Set<String>> routePatternsByMethod = new ConcurrentHashMap<>();
        Method targetMethod = DemoController.class.getMethod("detail");
        String pattern = "/api/ling/demo/detail";
        WebInterfaceMetadata metadata = conditionedMetadata(targetMethod, "GET", pattern, new String[0], new String[0], new String[0], new String[0]);
        String routeKey = "GET#/api/ling/demo/detail";
        metadataMap.put(routeKey, Collections.singletonList(metadata));
        routePatternsByMethod.put("GET", Collections.singleton(pattern));

        when(lingRepository.getRuntime("ling-a")).thenReturn(runtime);
        when(runtime.getReadyInstances()).thenReturn(Collections.singletonList(v1Instance));
        when(v1Instance.getVersion()).thenReturn("v1");

        // 无白名单（默认构造）：伪造 X-Forwarded-Prefix=/api 不应剥离前缀，仍按原路径命中
        DefaultWebRouteResolver noWhitelist = new DefaultWebRouteResolver(
                metadataMap, routePatternsByMethod, lingRepository, trafficRouter);
        MockHttpServletRequest forged = new MockHttpServletRequest("GET", "/api/ling/demo/detail");
        forged.addHeader("X-Forwarded-Prefix", "/api");
        assertNotNull(noWhitelist.resolveRoute(forged),
                "未配置白名单时不得采信伪造转发头做前缀剥离");

        // 白名单非空但不匹配伪造值：同样不采信
        DefaultWebRouteResolver mismatchedWhitelist = new DefaultWebRouteResolver(
                metadataMap, routePatternsByMethod, lingRepository, trafficRouter,
                Collections.singletonList("/real"));
        MockHttpServletRequest forged2 = new MockHttpServletRequest("GET", "/api/ling/demo/detail");
        forged2.addHeader("X-Forwarded-Prefix", "/api");
        assertNotNull(mismatchedWhitelist.resolveRoute(forged2),
                "伪造转发头不在白名单内时不得采信");

        // 白名单匹配才剥离：剥离后路径无法命中原 pattern → 视为路由失效（防止转移拦截）
        DefaultWebRouteResolver trustedWhitlist = new DefaultWebRouteResolver(
                metadataMap, routePatternsByMethod, lingRepository, trafficRouter,
                Collections.singletonList("/api"));
        MockHttpServletRequest trusted = new MockHttpServletRequest("GET", "/api/ling/demo/detail");
        trusted.addHeader("X-Forwarded-Prefix", "/api");
        assertNull(trustedWhitlist.resolveRoute(trusted),
                "白名单匹配时应剥离 /api 前缀，剥离后不应命中 /api 前缀路由");
    }

    static class DemoController {
        public String detail() {
            return "ok";
        }

        public String full() {
            return "full";
        }

        public String lite() {
            return "lite";
        }

        public String createJson() {
            return "json";
        }

        public String createText() {
            return "text";
        }

        public String overloaded(String value) {
            return value;
        }

        public String overloaded(Integer value) {
            return String.valueOf(value);
        }
    }
}
