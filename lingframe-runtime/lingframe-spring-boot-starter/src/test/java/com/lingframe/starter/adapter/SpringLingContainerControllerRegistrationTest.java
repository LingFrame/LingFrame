package com.lingframe.starter.adapter;

import com.lingframe.starter.web.WebInterfaceManager;
import com.lingframe.starter.web.WebInterfaceMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
@DisplayName("SpringLingContainer 控制器注册测试")
class SpringLingContainerControllerRegistrationTest {

    @Mock
    private WebInterfaceManager webInterfaceManager;

    @Test
    @DisplayName("应展开映射组合并保留请求条件")
    void shouldExpandMappingCombinationsAndPreserveConditions() throws Exception {
        SpringLingContainer container = new SpringLingContainer(
                null,
                MultiMappingController.class.getClassLoader(),
                webInterfaceManager,
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                Collections.emptyList(),
                "v1");

        MultiMappingController bean = new MultiMappingController();
        Method targetMethod = MultiMappingController.class.getMethod("upsert");
        RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(MultiMappingController.class,
                RequestMapping.class);
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(targetMethod, RequestMapping.class);
        assertNotNull(classMapping);
        assertNotNull(mapping);

        List<WebInterfaceMetadata> captured = new ArrayList<>();
        doAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return null;
        }).when(webInterfaceManager).registerSync(any(WebInterfaceMetadata.class));

        Method registerMethod = ReflectionUtils.findMethod(
                SpringLingContainer.class,
                "registerControllerMappings",
                String.class,
                String.class,
                Object.class,
                Method.class,
                RequestMapping.class,
                RequestMapping.class);
        assertNotNull(registerMethod);
        ReflectionUtils.makeAccessible(registerMethod);
        ReflectionUtils.invokeMethod(registerMethod, container, "ling-a", "multiMappingController",
                bean, targetMethod, classMapping, mapping);

        assertEquals(8, captured.size());
        Set<String> routes = captured.stream()
                .map(metadata -> metadata.getHttpMethod() + " " + metadata.getUrlPattern())
                .collect(Collectors.toSet());
        assertEquals(new HashSet<>(Arrays.asList(
                "GET /ling-a/api/v1",
                "POST /ling-a/api/v1",
                "GET /ling-a/api/v2",
                "POST /ling-a/api/v2",
                "GET /ling-a/alt/v1",
                "POST /ling-a/alt/v1",
                "GET /ling-a/alt/v2",
                "POST /ling-a/alt/v2")), routes);

        for (WebInterfaceMetadata metadata : captured) {
            assertEquals("multiMappingController", metadata.getTargetBeanName());
            assertEquals(MultiMappingController.class.getName(), metadata.getTargetClassName());
            assertEquals("upsert", metadata.getTargetMethodName());
            assertArrayEquals(new String[0], metadata.getTargetMethodParameterTypeNames());
            assertEquals("v1", metadata.getVersion());
            assertSame(bean, metadata.getTargetBean());
            assertArrayEquals(new String[] {"mode=full"}, metadata.getParams());
            assertArrayEquals(new String[] {"X-Test=1"}, metadata.getHeaders());
            assertArrayEquals(new String[] {"application/json"}, metadata.getConsumes());
            assertArrayEquals(new String[] {"application/json"}, metadata.getProduces());
            assertNotNull(metadata.getRequestMappingInfo());
            if ("POST".equals(metadata.getHttpMethod())) {
                assertTrue(metadata.isShouldAudit());
                assertTrue(metadata.getAuditAction().startsWith("POST /ling-a/"));
            } else {
                assertFalse(metadata.isShouldAudit());
            }
        }
    }

    @Test
    @DisplayName("应为继承方法记录真实 Controller 类名")
    void shouldCaptureConcreteControllerClassForInheritedMethod() throws Exception {
        SpringLingContainer container = new SpringLingContainer(
                null,
                InheritedController.class.getClassLoader(),
                webInterfaceManager,
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                Collections.emptyList(),
                "v1");

        InheritedController bean = new InheritedController();
        Method targetMethod = InheritedController.class.getMethod("detail");
        RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(InheritedController.class,
                RequestMapping.class);
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(targetMethod, RequestMapping.class);
        assertNotNull(classMapping);
        assertNotNull(mapping);

        List<WebInterfaceMetadata> captured = new ArrayList<>();
        doAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return null;
        }).when(webInterfaceManager).registerSync(any(WebInterfaceMetadata.class));

        Method registerMethod = ReflectionUtils.findMethod(
                SpringLingContainer.class,
                "registerControllerMappings",
                String.class,
                String.class,
                Object.class,
                Method.class,
                RequestMapping.class,
                RequestMapping.class);
        assertNotNull(registerMethod);
        ReflectionUtils.makeAccessible(registerMethod);
        ReflectionUtils.invokeMethod(registerMethod, container, "ling-a", "inheritedController",
                bean, targetMethod, classMapping, mapping);

        assertEquals(1, captured.size());
        WebInterfaceMetadata metadata = captured.get(0);
        assertEquals(InheritedController.class.getName(), metadata.getTargetClassName());
        assertEquals(InheritedBaseController.class.getName(), metadata.getTargetMethod().getDeclaringClass().getName());
    }

    @RestController
    @RequestMapping(path = {"/api", "/alt"})
    static class MultiMappingController {

        @RequestMapping(
                path = {"/v1", "/v2"},
                method = {RequestMethod.GET, RequestMethod.POST},
                consumes = {"application/json"},
                produces = {"application/json"},
                params = {"mode=full"},
                headers = {"X-Test=1"})
        public String upsert() {
            return "ok";
        }
    }

    static class InheritedBaseController {
        @RequestMapping(path = "/detail", method = RequestMethod.GET)
        public String detail() {
            return "ok";
        }
    }

    @RestController
    @RequestMapping(path = "/inherited")
    static class InheritedController extends InheritedBaseController {
    }
}
