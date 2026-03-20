package com.lingframe.starter.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("SpringWebHostSupport 测试")
class SpringWebHostSupportTest {

    @Test
    @DisplayName("应通过共享承载支持注册并清理兼容性产物")
    void shouldRegisterAndCleanupCompatibilityArtifacts() throws Exception {
        GenericApplicationContext hostContext = new GenericApplicationContext();
        hostContext.refresh();
        try {
            RequestMappingHandlerMapping hostMapping = mock(RequestMappingHandlerMapping.class);
            RequestMappingHandlerAdapter hostAdapter = createHostAdapter(hostContext);
            SpringWebHostSupport support = new SpringWebHostSupport();
            support.init(hostMapping, hostAdapter, hostContext);

            String beanName = "ling-a:v1:" + DemoController.class.getName();
            String routeKey = "GET#/ling-a/demo/detail";
            RequestMappingInfo mappingInfo = RequestMappingInfo.paths("/ling-a/demo/detail")
                    .methods(RequestMethod.GET)
                    .build();
            Map<String, RequestMappingInfo> mappingInfoMap = new ConcurrentHashMap<>();
            support.registerSpringDocBean(beanName, DemoController.class, DemoController::new);
            support.registerMapping(routeKey, mappingInfo, new Object(), Object.class.getMethods()[0], mappingInfoMap);

            assertTrue(hostContext.containsBeanDefinition(beanName));
            assertTrue(mappingInfoMap.containsKey(routeKey));

            support.cleanupCompatibilityArtifacts(
                    Collections.singleton(beanName),
                    Collections.singleton(routeKey),
                    mappingInfoMap,
                    DemoController.class.getClassLoader());

            assertFalse(hostContext.containsBeanDefinition(beanName));
            assertFalse(mappingInfoMap.containsKey(routeKey));
            verify(hostMapping).unregisterMapping(mappingInfo);
        } finally {
            hostContext.close();
        }
    }

    @Test
    @DisplayName("应通过共享承载适配桥调用目标控制器")
    void shouldInvokeTargetThroughHostAdapter() throws Exception {
        GenericApplicationContext hostContext = new GenericApplicationContext();
        hostContext.refresh();
        try {
            RequestMappingHandlerMapping hostMapping = mock(RequestMappingHandlerMapping.class);
            RequestMappingHandlerAdapter hostAdapter = createHostAdapter(hostContext);
            SpringWebHostSupport support = new SpringWebHostSupport();
            support.init(hostMapping, hostAdapter, hostContext);

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
                    .build();

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ling-a/demo/echo");
            request.addParameter("name", "alice");
            MockHttpServletResponse response = new MockHttpServletResponse();

            Object result = support.invokeTarget(metadata, metadata.buildRouteKey(), new ServletWebRequest(request, response));

            assertNull(result);
            assertEquals("alice", response.getContentAsString());
        } finally {
            hostContext.close();
        }
    }

    private RequestMappingHandlerAdapter createHostAdapter(GenericApplicationContext applicationContext) throws Exception {
        RequestMappingHandlerAdapter adapter = new RequestMappingHandlerAdapter();
        adapter.setApplicationContext(applicationContext);
        adapter.setMessageConverters(Collections.singletonList(new StringHttpMessageConverter()));
        adapter.afterPropertiesSet();
        return adapter;
    }

    static class DemoController {
        @ResponseBody
        public String echo(@RequestParam("name") String name) {
            return name;
        }
    }
}
