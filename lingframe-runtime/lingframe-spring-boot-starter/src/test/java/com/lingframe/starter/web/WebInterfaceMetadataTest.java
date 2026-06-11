package com.lingframe.starter.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("WebInterfaceMetadata 测试")
class WebInterfaceMetadataTest {

    public static class DemoController {
        public void testMethod(String arg) {}
    }

    @Test
    @DisplayName("全面测试 WebInterfaceMetadata 属性、生命周期及匹配逻辑")
    void testMetadataLifecycleAndMatching() throws Exception {
        Method method = DemoController.class.getMethod("testMethod", String.class);
        DemoController controller = new DemoController();
        ApplicationContext context = mock(ApplicationContext.class);

        WebInterfaceMetadata metadata = WebInterfaceMetadata.builder()
                .lingId("test-ling")
                .targetBean(controller)
                .targetMethod(method)
                .classLoader(DemoController.class.getClassLoader())
                .lingApplicationContext(context)
                .urlPattern("/test/path")
                .httpMethod("GET")
                .params(new String[]{"a=1"})
                .headers(new String[]{"h=2"})
                .consumes(new String[]{"application/json"})
                .produces(new String[]{"text/plain"})
                .build();

        // 1. 签名与基本属性获取
        assertEquals("test-ling", metadata.getLingId());
        assertEquals(controller, metadata.getTargetBean());
        assertEquals(method, metadata.getTargetMethod());
        assertEquals(DemoController.class.getClassLoader(), metadata.getClassLoader());
        assertEquals(context, metadata.getLingApplicationContext());
        assertEquals(DemoController.class, metadata.getTargetClass());

        // 2. buildRouteKey
        assertEquals("GET#/test/path|params=[a=1];headers=[h=2];consumes=[application/json];produces=[text/plain]", metadata.buildRouteKey());

        // 3. matchesHandler
        assertTrue(metadata.matchesHandler(controller, method));
        assertFalse(metadata.matchesHandler(new Object(), method));

        // 4. hasSameTargetSignature
        WebInterfaceMetadata other = WebInterfaceMetadata.builder()
                .targetClassName(DemoController.class.getName())
                .targetMethodName(method.getName())
                .targetMethodParameterTypeNames(new String[]{String.class.getName()})
                .build();
        assertTrue(metadata.hasSameTargetSignature(other));

        // 5. matchesRequest 的各个分支
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test/path");
        request.setParameter("a", "1");
        request.addHeader("h", "2");
        request.setContentType("application/json");
        request.addHeader("Accept", "text/plain");

        assertTrue(metadata.matchesRequest(request));

        // 6. compareRequestSpecificity 与权重计算
        WebInterfaceMetadata simpleMeta = WebInterfaceMetadata.builder().build();
        assertEquals(-1, metadata.compareRequestSpecificity(simpleMeta, request));

        // 7. snapshotForRequest
        WebInterfaceMetadata snapshot = metadata.snapshotForRequest();
        assertEquals(metadata.getLingId(), snapshot.getLingId());
        assertEquals(metadata.getUrlPattern(), snapshot.getUrlPattern());

        // 8. minimizeHostReferences
        metadata.minimizeHostReferences();
        assertEquals(controller, metadata.getTargetBean());

        // 9. clearReferences
        metadata.clearReferences();
        assertNull(metadata.getTargetBean());
        assertNull(metadata.getTargetMethod());
    }
}
