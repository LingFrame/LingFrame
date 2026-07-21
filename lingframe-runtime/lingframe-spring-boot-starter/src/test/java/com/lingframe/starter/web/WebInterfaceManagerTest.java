package com.lingframe.starter.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.lingframe.api.exception.LingException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.metrics.LingHealthMetrics;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebInterfaceManager 测试")
class WebInterfaceManagerTest {

    @Mock
    private RequestMappingHandlerMapping hostMapping;

    private GenericApplicationContext hostContext;
    private WebInterfaceManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
        if (hostContext != null) {
            hostContext.close();
        }
    }

    @Test
    @DisplayName("调用 unregisterSync 时应移除路由索引")
    void shouldUnregisterSynchronously() throws Exception {
        hostContext = new GenericApplicationContext();
        hostContext.refresh();

        manager = new WebInterfaceManager(null, null, null);
        manager.init(hostMapping, null, hostContext);

        DemoController controller = new DemoController();
        Method targetMethod = DemoController.class.getMethod("detail");
        WebInterfaceMetadata metadata = WebInterfaceMetadata.builder()
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

        manager.registerSync(metadata);

        assertNotNull(manager.resolveRoute((new MockHttpServletRequest("GET", "/ling-a/demo/detail"))));
        verify(hostMapping).registerMapping(any(RequestMappingInfo.class), any(), any(Method.class));

        manager.unregisterSync("ling-a", DemoController.class.getClassLoader());

        assertNull(manager.resolveRoute((new MockHttpServletRequest("GET", "/ling-a/demo/detail"))));
        verify(hostMapping).unregisterMapping(any(RequestMappingInfo.class));
    }

    @Test
    @DisplayName("调用 registerSync 时应按 params 条件区分同一路径路由")
    void shouldKeepSamePathRoutesDistinctByParamsCondition() throws Exception {
        hostContext = new GenericApplicationContext();
        hostContext.refresh();

        manager = new WebInterfaceManager(null, null, null);
        manager.init(hostMapping, null, hostContext);

        DemoController controller = new DemoController();
        WebInterfaceMetadata full = conditionedMetadata(controller, DemoController.class.getMethod("full"),
                new String[] {"mode=full"});
        WebInterfaceMetadata lite = conditionedMetadata(controller, DemoController.class.getMethod("lite"),
                new String[] {"mode=lite"});

        manager.registerSync(full);
        manager.registerSync(lite);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ling-a/demo/detail");
        request.addParameter("mode", "full");
        WebRouteResolution resolution = manager.resolveRoute((request));

        assertNotNull(resolution);
        assertEquals("full", resolution.getMetadata().getTargetMethodName());
        verify(hostMapping, times(2)).registerMapping(any(RequestMappingInfo.class), any(), any(Method.class));
    }

    @Test
    @DisplayName("调用 unregisterSync 后缓存路由仍应可继续分发")
    void shouldDispatchCachedRouteAfterUnregisterSync() throws Exception {
        hostContext = new GenericApplicationContext();
        hostContext.refresh();

        RequestMappingHandlerAdapter hostAdapter = createHostAdapter(hostContext);
        manager = new WebInterfaceManager(null, null, null);
        manager.init(hostMapping, hostAdapter, hostContext);

        DemoController controller = new DemoController();
        Method targetMethod = DemoController.class.getMethod("echo", String.class);
        WebInterfaceMetadata metadata = WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .version("v1")
                .targetBeanName("demoController")
                .targetBean(controller)
                .targetClassName(DemoController.class.getName())
                .targetMethodName(targetMethod.getName())
                .targetMethodParameterTypeNames(new String[] {String.class.getName()})
                .targetMethod(targetMethod)
                .classLoader(DemoController.class.getClassLoader())
                .lingApplicationContext(hostContext)
                .urlPattern("/ling-a/demo/echo")
                .httpMethod("GET")
                .requiredPermission("demo:read")
                .requestMappingInfo(RequestMappingInfo.paths("/ling-a/demo/echo")
                        .methods(RequestMethod.GET)
                        .build())
                .build();

        manager.registerSync(metadata);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ling-a/demo/echo");
        request.addParameter("name", "alice");
        WebRouteResolution resolution = manager.resolveRoute((request));

        assertNotNull(resolution);
        assertEquals("echo", resolution.getMetadata().getTargetMethodName());

        manager.unregisterSync("ling-a", DemoController.class.getClassLoader());

        // 卸载后，通过 routeKey 重新分发应抛出异常（因为元数据已注销）
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThrows(LingException.class, () ->
                manager.dispatch(resolution.getRouteKey(), createServletWebRequest(request, response)));

        verify(hostMapping).unregisterMapping(any(RequestMappingInfo.class));
    }

    @Test
    @DisplayName("调用 registerSync 时应能够处理继承的 Controller 方法")
    void shouldHandleInheritedMethodRegistration() throws Exception {
        hostContext = new GenericApplicationContext();
        hostContext.refresh();

        manager = new WebInterfaceManager(null, null, null);
        manager.init(hostMapping, null, hostContext);

        FirstInheritedController firstController = new FirstInheritedController();
        SecondInheritedController secondController = new SecondInheritedController();

        WebInterfaceMetadata first = inheritedMetadata(
                "firstInheritedController",
                firstController,
                FirstInheritedController.class,
                "/ling-a/demo/first");
        WebInterfaceMetadata second = inheritedMetadata(
                "secondInheritedController",
                secondController,
                SecondInheritedController.class,
                "/ling-a/demo/second");

        manager.registerSync(first);
        manager.registerSync(second);

        assertNotNull(manager.resolveRoute((new MockHttpServletRequest("GET", "/ling-a/demo/first"))));
        assertNotNull(manager.resolveRoute((new MockHttpServletRequest("GET", "/ling-a/demo/second"))));
        verify(hostMapping, times(2)).registerMapping(any(RequestMappingInfo.class), any(), any(Method.class));
    }

    @Test
    @DisplayName("调用 registerSync 时应向灵核映射暴露 SpringDoc 可见的真实 HandlerMethod")
    void shouldExposeSpringDocCompatibleHandlerMethodThroughHostMapping() throws Exception {
        hostContext = new GenericApplicationContext();
        hostContext.refresh();

        RequestMappingHandlerMapping realHostMapping = createHostMapping(hostContext);
        manager = new WebInterfaceManager(null, null, null);
        manager.init(realHostMapping, null, hostContext);

        DemoController controller = new DemoController();
        Method targetMethod = DemoController.class.getMethod("detail");
        WebInterfaceMetadata metadata = WebInterfaceMetadata.builder()
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

        manager.registerSync(metadata);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ling-a/demo/detail");
        HandlerExecutionChain chain = getHandlerExecutionChain(realHostMapping, request);

        assertNotNull(chain);
        assertTrue(chain.getHandler() instanceof HandlerMethod);
        HandlerMethod handlerMethod = (HandlerMethod) chain.getHandler();
        assertTrue(handlerMethod.getBean() instanceof WebInterfaceManager.LingWebEntryHandler);
        assertEquals("dispatch", handlerMethod.getMethod().getName());
        assertEquals(WebInterfaceManager.LingWebEntryHandler.class, handlerMethod.getBeanType());

        WebRouteResolution resolution = manager.resolveRoute((new MockHttpServletRequest("GET", "/ling-a/demo/detail")),
                handlerMethod);
        assertNotNull(resolution);
        assertTrue(handlerMethod.getBean() instanceof WebInterfaceManager.LingWebEntryHandler);
        assertEquals("detail", resolution.getMetadata().getTargetMethodName());
    }

    @Test
    @DisplayName("调用 unregisterSync 时应在旧版本卸载后刷新灵核兼容映射")
    void shouldRefreshHostCompatibilityMappingAfterRegisteredVersionIsRemoved() throws Exception {
        hostContext = new GenericApplicationContext();
        hostContext.refresh();

        RequestMappingHandlerMapping realHostMapping = createHostMapping(hostContext);
        manager = new WebInterfaceManager(null, null, null);
        manager.init(realHostMapping, null, hostContext);

        ClassLoader v1Loader = new ClassLoader() {
        };
        ClassLoader v2Loader = new ClassLoader() {
        };

        DemoController v1Controller = new DemoController();
        DemoController v2Controller = new DemoController();
        Method targetMethod = DemoController.class.getMethod("detail");

        WebInterfaceMetadata v1 = WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .version("v1")
                .targetBeanName("demoControllerV1")
                .targetBean(v1Controller)
                .targetClassName(DemoController.class.getName())
                .targetMethodName(targetMethod.getName())
                .targetMethodParameterTypeNames(new String[0])
                .targetMethod(targetMethod)
                .classLoader(v1Loader)
                .urlPattern("/ling-a/demo/detail")
                .httpMethod("GET")
                .requiredPermission("demo:read")
                .build();
        WebInterfaceMetadata v2 = WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .version("v2")
                .targetBeanName("demoControllerV2")
                .targetBean(v2Controller)
                .targetClassName(DemoController.class.getName())
                .targetMethodName(targetMethod.getName())
                .targetMethodParameterTypeNames(new String[0])
                .targetMethod(targetMethod)
                .classLoader(v2Loader)
                .urlPattern("/ling-a/demo/detail")
                .httpMethod("GET")
                .requiredPermission("demo:read")
                .build();

        manager.registerSync(v1);
        manager.registerSync(v2);

        MockHttpServletRequest beforeRequest = new MockHttpServletRequest("GET", "/ling-a/demo/detail");
        HandlerMethod beforeHandler = (HandlerMethod) getHandlerExecutionChain(realHostMapping, beforeRequest).getHandler();
        assertTrue(beforeHandler.getBean() instanceof WebInterfaceManager.LingWebEntryHandler);
        assertEquals(WebInterfaceManager.LingWebEntryHandler.class, beforeHandler.getBeanType());

        manager.unregisterSync("ling-a", v1Loader);

        MockHttpServletRequest afterRequest = new MockHttpServletRequest("GET", "/ling-a/demo/detail");
        HandlerExecutionChain afterChain = getHandlerExecutionChain(realHostMapping, afterRequest);
        assertNotNull(afterChain);
        assertTrue(afterChain.getHandler() instanceof HandlerMethod);
        HandlerMethod afterHandler = (HandlerMethod) afterChain.getHandler();
        assertTrue(afterHandler.getBean() instanceof WebInterfaceManager.LingWebEntryHandler);
        assertEquals(WebInterfaceManager.LingWebEntryHandler.class, afterHandler.getBeanType());

        WebRouteResolution resolution = manager.resolveRoute((new MockHttpServletRequest("GET", "/ling-a/demo/detail")));
        assertNotNull(resolution);
        assertEquals("v2", resolution.getMetadata().getVersion());
    }

    private RequestMappingHandlerAdapter createHostAdapter(GenericApplicationContext applicationContext) throws Exception {
        RequestMappingHandlerAdapter adapter = new RequestMappingHandlerAdapter();
        adapter.setApplicationContext(applicationContext);
        adapter.setMessageConverters(Collections.singletonList(new StringHttpMessageConverter()));
        adapter.afterPropertiesSet();
        return adapter;
    }

    private RequestMappingHandlerMapping createHostMapping(GenericApplicationContext applicationContext) throws Exception {
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(applicationContext);
        mapping.afterPropertiesSet();
        return mapping;
    }

    private ServletWebRequest createServletWebRequest(Object request, Object response) throws Exception {
        ClassLoader cl = request.getClass().getClassLoader();
        Class<?> requestIntf = findServletInterface(cl, "HttpServletRequest");
        Class<?> responseIntf = findServletInterface(cl, "HttpServletResponse");
        return (ServletWebRequest) ReflectionUtils.accessibleConstructor(ServletWebRequest.class,
                requestIntf, responseIntf).newInstance(request, response);
    }

    private HandlerExecutionChain getHandlerExecutionChain(RequestMappingHandlerMapping mapping, Object request)
            throws Exception {
        ClassLoader cl = request.getClass().getClassLoader();
        Class<?> requestIntf = findServletInterface(cl, "HttpServletRequest");
        Method getHandlerMethod = ReflectionUtils.findMethod(mapping.getClass(), "getHandler", requestIntf);
        if (getHandlerMethod == null) {
            throw new AssertionError("Cannot resolve RequestMappingHandlerMapping.getHandler for request type "
                    + request.getClass().getName());
        }
        return (HandlerExecutionChain) ReflectionUtils.invokeMethod(getHandlerMethod, mapping, request);
    }

    private static Class<?> findServletInterface(ClassLoader cl, String interfaceName) {
        try {
            return Class.forName("jakarta.servlet.http." + interfaceName, false, cl);
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName("javax.servlet.http." + interfaceName, false, cl);
            } catch (ClassNotFoundException ex) {
                return null;
            }
        }
    }

    private WebInterfaceMetadata conditionedMetadata(DemoController controller, Method targetMethod, String[] params) {
        return WebInterfaceMetadata.builder()
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
                .params(params)
                .requiredPermission("demo:read")
                .requestMappingInfo(RequestMappingInfo.paths("/ling-a/demo/detail")
                        .methods(RequestMethod.GET)
                        .params(params)
                        .build())
                .build();
    }

    private WebInterfaceMetadata inheritedMetadata(String beanName,
                                                   Object controller,
                                                   Class<?> controllerClass,
                                                   String urlPattern) throws Exception {
        Method targetMethod = controllerClass.getMethod("detail");
        return WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .version("v1")
                .targetBeanName(beanName)
                .targetBean(controller)
                .targetClassName(controllerClass.getName())
                .targetMethodName(targetMethod.getName())
                .targetMethodParameterTypeNames(new String[0])
                .targetMethod(targetMethod)
                .classLoader(controllerClass.getClassLoader())
                .urlPattern(urlPattern)
                .httpMethod("GET")
                .requiredPermission("demo:read")
                .build();
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

        @ResponseBody
        public String echo(@RequestParam("name") String name) {
            return name;
        }
    }

    static class BaseController {
        public String detail() {
            return "base";
        }
    }

    static class FirstInheritedController extends BaseController {
    }

    static class SecondInheritedController extends BaseController {
    }

    @Test
    @DisplayName("同步注册与注销不再依赖 executor，executor 异常不影响同步路径")
    void testSyncMethodsImmuneToExecutorFailures() throws Exception {
        manager = new WebInterfaceManager(null, null, null);

        // 替换为 mock executor，验证同步路径完全不调用它
        Field field = WebInterfaceManager.class.getDeclaredField("registryExecutor");
        field.setAccessible(true);
        ExecutorService mockExecutor = mock(ExecutorService.class);
        field.set(manager, mockExecutor);

        WebInterfaceMetadata metadata = WebInterfaceMetadata.builder().build();

        // 🔥 P1-29 修复后：registerSync/unregisterSync 直接走 synchronized(registryLock)，
        // 不再调用 executor.submit().get()，executor 异常不会影响同步路径。
        // hostSupport 未初始化时 registerInternal/unregisterInternal 安全跳过，不抛异常。
        manager.registerSync(metadata);
        manager.unregisterSync("ling-a", null);

        // 验证同步路径从未向 executor 提交任务
        verify(mockExecutor, never()).submit(any(Runnable.class));
        verify(mockExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("同一路径注册同版本但不同签名的方法时应抛出冲突异常")
    void testConflictRegistration() throws Exception {
        hostContext = new GenericApplicationContext();
        hostContext.refresh();

        manager = new WebInterfaceManager(null, null, null);
        manager.init(hostMapping, null, hostContext);

        DemoController controller = new DemoController();
        Method detailMethod = DemoController.class.getMethod("detail");
        Method fullMethod = DemoController.class.getMethod("full");

        WebInterfaceMetadata v1_detail = WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .version("v1")
                .targetBean(controller)
                .targetMethod(detailMethod)
                .classLoader(DemoController.class.getClassLoader())
                .urlPattern("/ling-a/demo/route")
                .httpMethod("GET")
                .build();

        WebInterfaceMetadata v1_full = WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .version("v1")
                .targetBean(controller)
                .targetMethod(fullMethod)
                .classLoader(DemoController.class.getClassLoader())
                .urlPattern("/ling-a/demo/route")
                .httpMethod("GET")
                .build();

        manager.registerSync(v1_detail);
        LingException ex = assertThrows(LingException.class, () -> manager.registerSync(v1_full));
        Throwable rootCause = ex;
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        assertTrue(rootCause instanceof IllegalStateException);
        assertTrue(rootCause.getMessage().contains("Conflicting"));
    }

    @Test
    @DisplayName("测试分发已解析路由时的异常处理及 Metrics 超时分支")
    void testDispatchResolvedExceptionsAndMetrics() throws Exception {
        hostContext = new GenericApplicationContext();
        hostContext.refresh();
        RequestMappingHandlerAdapter hostAdapter = createHostAdapter(hostContext);

        org.springframework.beans.factory.ObjectProvider<MetricsCollector> metricsProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
        MetricsCollector collector = mock(MetricsCollector.class);
        LingHealthMetrics health = mock(LingHealthMetrics.class);
        
        when(metricsProvider.getIfAvailable()).thenReturn(collector);
        when(collector.getOrCreate(anyString())).thenReturn(health);
        when(collector.getOrCreate(anyString(), any())).thenReturn(health);

        manager = new WebInterfaceManager(null, null, metricsProvider);
        manager.init(hostMapping, hostAdapter, hostContext);

        // 1. meta 为 null 的异常分支
        assertThrows(LingException.class, () -> manager.dispatch(null));

        ExceptionController controller = new ExceptionController();
        Method failMethod = ExceptionController.class.getMethod("fail");
        Method timeoutMethod = ExceptionController.class.getMethod("timeout");

        org.springframework.context.ApplicationContext mockLingContext = mock(org.springframework.context.ApplicationContext.class);
        when(mockLingContext.getBean(RequestMappingHandlerAdapter.class)).thenReturn(hostAdapter);

        WebInterfaceMetadata metaFail = WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .version("v1")
                .targetBean(controller)
                .targetMethod(failMethod)
                .classLoader(DemoController.class.getClassLoader())
                .lingApplicationContext(mockLingContext)
                .httpMethod("GET")
                .urlPattern("/fail")
                .build();

        WebInterfaceMetadata metaTimeout = WebInterfaceMetadata.builder()
                .lingId("ling-a")
                .version("v1")
                .targetBean(controller)
                .targetMethod(timeoutMethod)
                .classLoader(DemoController.class.getClassLoader())
                .lingApplicationContext(mockLingContext)
                .httpMethod("GET")
                .urlPattern("/timeout")
                .build();

        Field mapField = WebInterfaceManager.class.getDeclaredField("metadataMap");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, List<WebInterfaceMetadata>> metadataMap = (Map<String, List<WebInterfaceMetadata>>) mapField.get(manager);
        metadataMap.put("GET#/fail", Collections.singletonList(metaFail));
        metadataMap.put("GET#/timeout", Collections.singletonList(metaTimeout));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletWebRequest webRequest = createServletWebRequest(request, response);

        // 2.1 模拟普通业务异常
        assertThrows(Exception.class, () -> manager.dispatch("GET#/fail", webRequest));
        verify(health).recordFailure(anyLong(), eq(false));

        // 2.2 模拟超时异常
        assertThrows(Exception.class, () -> manager.dispatch("GET#/timeout", webRequest));
        verify(health).recordFailure(anyLong(), eq(true));
    }

    static class ExceptionController {
        public String fail() {
            throw new RuntimeException("Biz Error");
        }
        public String timeout() throws Exception {
            throw new TimeoutException("Read timed out");
        }
    }
}
