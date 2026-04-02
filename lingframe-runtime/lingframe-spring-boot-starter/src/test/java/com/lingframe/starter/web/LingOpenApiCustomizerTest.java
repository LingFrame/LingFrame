package com.lingframe.starter.web;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("LingOpenApiCustomizer 测试")
class LingOpenApiCustomizerTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("分组 api-doc 请求应按 SpringDoc 分组配置注入灵元路径")
    void shouldInjectLingPathsForGroupedApiDocRequest() throws Exception {
        Method targetMethod = DemoController.class.getMethod("listUsers");
        WebInterfaceMetadata metadata = WebInterfaceMetadata.builder()
                .lingId("user-ling")
                .version("1.0.0")
                .targetBeanName("demoLingController")
                .targetBean(new DemoController())
                .targetClassName(DemoController.class.getName())
                .targetMethodName(targetMethod.getName())
                .targetMethodParameterTypeNames(new String[0])
                .targetMethod(targetMethod)
                .classLoader(DemoController.class.getClassLoader())
                .urlPattern("/user-ling/user/listUsers")
                .httpMethod("GET")
                .opSummary("list users")
                .build();

        WebInterfaceManager manager = new WebInterfaceManager(null, null, null) {
            @Override
            public Map<String, List<WebInterfaceMetadata>> getMetadataMap() {
                return Collections.singletonMap(metadata.buildRouteKey(), Collections.singletonList(metadata));
            }
        };

        MockEnvironment environment = new MockEnvironment()
                .withProperty("springdoc.group-configs[0].group", "lings")
                .withProperty("springdoc.group-configs[0].paths-to-match", "/**-ling/**");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v3/api-docs/lings");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request, new MockHttpServletResponse()));

        LingOpenApiCustomizer customizer = new LingOpenApiCustomizer(manager, environment);
        OpenAPI openApi = new OpenAPI();

        customizer.customise(openApi);

        assertNotNull(openApi.getPaths());
        assertNotNull(openApi.getPaths().get("/user-ling/user/listUsers"));
        assertEquals("list users",
                openApi.getPaths().get("/user-ling/user/listUsers").getGet().getSummary());
    }

    static class DemoController {
        public String listUsers() {
            return "ok";
        }
    }
}
