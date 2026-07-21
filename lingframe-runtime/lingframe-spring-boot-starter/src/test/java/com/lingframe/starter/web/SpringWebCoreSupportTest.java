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
import org.springframework.util.ReflectionUtils;
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

@DisplayName("SpringWebCoreSupport 测试")
class SpringWebCoreSupportTest {

    @Test
    @DisplayName("应通过共享承载支持注册并清理兼容性产物")
    void shouldRegisterAndCleanupCompatibilityArtifacts() throws Exception {
        GenericApplicationContext coreContext = new GenericApplicationContext();
        coreContext.refresh();
        try {
            RequestMappingHandlerMapping coreMapping = mock(RequestMappingHandlerMapping.class);
            RequestMappingHandlerAdapter coreAdapter = createCoreAdapter(coreContext);
            SpringWebCoreSupport support = new SpringWebCoreSupport();
            support.init(coreMapping, coreAdapter, coreContext);

            String routeKey = "GET#/ling-a/demo/detail";
            RequestMappingInfo mappingInfo = RequestMappingInfo.paths("/ling-a/demo/detail")
                    .methods(RequestMethod.GET)
                    .build();
            Map<String, RequestMappingInfo> mappingInfoMap = new ConcurrentHashMap<>();
            support.registerMapping(routeKey, mappingInfo, new Object(), Object.class.getMethods()[0], mappingInfoMap);

            assertTrue(mappingInfoMap.containsKey(routeKey));

            support.unregisterMappings(Collections.singleton(routeKey), mappingInfoMap, null);

            assertFalse(mappingInfoMap.containsKey(routeKey));
            verify(coreMapping).unregisterMapping(mappingInfo);
        } finally {
            coreContext.close();
        }
    }

    @Test
    @DisplayName("应通过共享承载适配桥调用目标控制器")
    void shouldInvokeTargetThroughCoreAdapter() throws Exception {
        GenericApplicationContext coreContext = new GenericApplicationContext();
        coreContext.refresh();
        try {
            RequestMappingHandlerMapping coreMapping = mock(RequestMappingHandlerMapping.class);
            RequestMappingHandlerAdapter coreAdapter = createCoreAdapter(coreContext);
            SpringWebCoreSupport support = new SpringWebCoreSupport();
            support.init(coreMapping, coreAdapter, coreContext);

            DemoController controller = new DemoController();
            Method targetMethod = DemoController.class.getMethod("echo", String.class);
            GenericApplicationContext lingContext = new GenericApplicationContext();
            lingContext.refresh();
            lingContext.getBeanFactory().registerSingleton("requestMappingHandlerAdapter", coreAdapter);

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
                    .lingApplicationContext(lingContext)
                    .urlPattern("/ling-a/demo/echo")
                    .httpMethod("GET")
                    .requiredPermission("demo:read")
                    .build();

            Object request = new MockHttpServletRequest("GET", "/ling-a/demo/echo");
            Method addParamMethod = ReflectionUtils.findMethod(request.getClass(), "addParameter", String.class, String.class);
            if (addParamMethod != null) {
                ReflectionUtils.invokeMethod(addParamMethod, request, "name", "alice");
            }
            Object response = new MockHttpServletResponse();
            
            // 动态构造 ServletWebRequest
            ServletWebRequest webRequest = createServletWebRequest(request, response);
            Object result = support.invokeTarget(metadata, metadata.buildRouteKey(), webRequest);

            assertNull(result);
            Method getContentMethod = ReflectionUtils.findMethod(response.getClass(), "getContentAsString");
            if (getContentMethod != null) {
                assertEquals("alice", ReflectionUtils.invokeMethod(getContentMethod, response));
            }
        } finally {
            coreContext.close();
        }
    }

    private RequestMappingHandlerAdapter createCoreAdapter(GenericApplicationContext applicationContext) throws Exception {
        RequestMappingHandlerAdapter adapter = new RequestMappingHandlerAdapter();
        adapter.setApplicationContext(applicationContext);
        adapter.setMessageConverters(Collections.singletonList(new StringHttpMessageConverter()));
        adapter.afterPropertiesSet();
        return adapter;
    }

    private ServletWebRequest createServletWebRequest(Object request, Object response) throws Exception {
        ClassLoader cl = request.getClass().getClassLoader();
        Class<?> requestIntf = findServletInterface(cl, "HttpServletRequest");
        Class<?> responseIntf = findServletInterface(cl, "HttpServletResponse");

        return (ServletWebRequest) ReflectionUtils.accessibleConstructor(ServletWebRequest.class, 
            requestIntf, responseIntf).newInstance(request, response);
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

    static class DemoController {
        @ResponseBody
        public String echo(@RequestParam("name") String name) {
            return name;
        }
    }
}
