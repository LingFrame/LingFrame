package com.lingframe.starter.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
    @DisplayName("unregisterSync 应移除路由索引与 SpringDoc Bean 定义")
    void shouldUnregisterSynchronously() throws Exception {
        hostContext = new GenericApplicationContext();
        hostContext.refresh();

        manager = new WebInterfaceManager(null, null);
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

        String springDocBeanName = "ling-a:v1:" + DemoController.class.getName();
        assertEquals(springDocBeanName, metadata.getSpringDocBeanName());
        assertTrue(hostContext.containsBeanDefinition(springDocBeanName));
        assertNotNull(manager.resolveRoute(new MockHttpServletRequest("GET", "/ling-a/demo/detail")));
        verify(hostMapping).registerMapping(any(RequestMappingInfo.class), any(), any(Method.class));

        manager.unregisterSync("ling-a", DemoController.class.getClassLoader());

        assertFalse(hostContext.containsBeanDefinition(springDocBeanName));
        assertNull(manager.resolveRoute(new MockHttpServletRequest("GET", "/ling-a/demo/detail")));
        verify(hostMapping).unregisterMapping(any(RequestMappingInfo.class));
    }

    @Test
    @DisplayName("registerSync 应按 params 条件区分同一路径路由")
    void shouldKeepSamePathRoutesDistinctByParamsCondition() throws Exception {
        hostContext = new GenericApplicationContext();
        hostContext.refresh();

        manager = new WebInterfaceManager(null, null);
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
        WebRouteResolution resolution = manager.resolveRoute(request);

        assertNotNull(resolution);
        assertEquals("full", resolution.getMetadata().getTargetMethodName());
        verify(hostMapping, times(2)).registerMapping(any(RequestMappingInfo.class), any(), any(Method.class));
    }

    @Test
    @DisplayName("unregisterSync 后缓存路由仍应可继续分发")
    void shouldDispatchCachedRouteAfterUnregisterSync() throws Exception {
        hostContext = new GenericApplicationContext();
        hostContext.refresh();

        RequestMappingHandlerAdapter hostAdapter = createHostAdapter(hostContext);
        manager = new WebInterfaceManager(null, null);
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
        WebRouteResolution resolution = manager.resolveRoute(request);

        assertNotNull(resolution);
        assertEquals("echo", resolution.getMetadata().getTargetMethodName());

        manager.unregisterSync("ling-a", DemoController.class.getClassLoader());

        MockHttpServletResponse response = new MockHttpServletResponse();
        Object result = manager.dispatch(resolution.getRouteKey(), new ServletWebRequest(request, response));

        assertNull(result);
        assertEquals("alice", response.getContentAsString());
        verify(hostMapping).unregisterMapping(any(RequestMappingInfo.class));
    }

    @Test
    @DisplayName("registerSync 应使用真实 Controller 类注册继承方法的 SpringDoc Bean")
    void shouldRegisterSpringDocBeanWithResolvedControllerClassForInheritedMethod() throws Exception {
        hostContext = new GenericApplicationContext();
        hostContext.refresh();

        manager = new WebInterfaceManager(null, null);
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

        String firstBeanName = "ling-a:v1:" + FirstInheritedController.class.getName();
        String secondBeanName = "ling-a:v1:" + SecondInheritedController.class.getName();
        String baseBeanName = "ling-a:v1:" + BaseController.class.getName();

        assertEquals(firstBeanName, first.getSpringDocBeanName());
        assertEquals(secondBeanName, second.getSpringDocBeanName());
        assertTrue(hostContext.containsBeanDefinition(firstBeanName));
        assertTrue(hostContext.containsBeanDefinition(secondBeanName));
        assertFalse(hostContext.containsBeanDefinition(baseBeanName));

        GenericBeanDefinition firstBeanDefinition =
                (GenericBeanDefinition) hostContext.getBeanFactory().getBeanDefinition(firstBeanName);
        GenericBeanDefinition secondBeanDefinition =
                (GenericBeanDefinition) hostContext.getBeanFactory().getBeanDefinition(secondBeanName);
        assertEquals(FirstInheritedController.class, firstBeanDefinition.getBeanClass());
        assertEquals(SecondInheritedController.class, secondBeanDefinition.getBeanClass());
    }

    @Test
    @DisplayName("registerSync 应向宿主映射暴露 SpringDoc 可见的真实 HandlerMethod")
    void shouldExposeSpringDocCompatibleHandlerMethodThroughHostMapping() throws Exception {
        hostContext = new GenericApplicationContext();
        hostContext.refresh();

        RequestMappingHandlerMapping realHostMapping = createHostMapping(hostContext);
        manager = new WebInterfaceManager(null, null);
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
        HandlerExecutionChain chain = realHostMapping.getHandler(request);

        assertNotNull(chain);
        assertTrue(chain.getHandler() instanceof HandlerMethod);
        HandlerMethod handlerMethod = (HandlerMethod) chain.getHandler();
        assertSame(controller, handlerMethod.getBean());
        assertEquals(targetMethod, handlerMethod.getMethod());
        assertEquals(DemoController.class, handlerMethod.getBeanType());

        WebRouteResolution resolution = manager.resolveRoute(new MockHttpServletRequest("GET", "/ling-a/demo/detail"),
                handlerMethod);
        assertNotNull(resolution);
        assertSame(controller, handlerMethod.getBean());
        assertEquals("detail", resolution.getMetadata().getTargetMethodName());
    }

    @Test
    @DisplayName("unregisterSync 应在旧版本卸载后刷新宿主兼容映射")
    void shouldRefreshHostCompatibilityMappingAfterRegisteredVersionIsRemoved() throws Exception {
        hostContext = new GenericApplicationContext();
        hostContext.refresh();

        RequestMappingHandlerMapping realHostMapping = createHostMapping(hostContext);
        manager = new WebInterfaceManager(null, null);
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
        HandlerMethod beforeHandler = (HandlerMethod) realHostMapping.getHandler(beforeRequest).getHandler();
        assertSame(v1Controller, beforeHandler.getBean());

        manager.unregisterSync("ling-a", v1Loader);

        MockHttpServletRequest afterRequest = new MockHttpServletRequest("GET", "/ling-a/demo/detail");
        HandlerExecutionChain afterChain = realHostMapping.getHandler(afterRequest);
        assertNotNull(afterChain);
        assertTrue(afterChain.getHandler() instanceof HandlerMethod);
        HandlerMethod afterHandler = (HandlerMethod) afterChain.getHandler();
        assertSame(v2Controller, afterHandler.getBean());
        assertEquals(DemoController.class, afterHandler.getBeanType());

        WebRouteResolution resolution = manager.resolveRoute(new MockHttpServletRequest("GET", "/ling-a/demo/detail"));
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
}
