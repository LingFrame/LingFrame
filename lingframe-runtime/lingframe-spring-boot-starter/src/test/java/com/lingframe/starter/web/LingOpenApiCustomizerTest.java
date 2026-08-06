package com.lingframe.starter.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@DisplayName("LingOpenApiCustomizer 测试")
class LingOpenApiCustomizerTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("分组 api-doc 请求应按 SpringDoc 分组配置注入灵元路径，tag 只信 metadata")
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
                .opTags(new String[]{"Users", "Ling"})
                .opTagDescription("Users module from class @Tag")
                .build();

        WebInterfaceManager manager = new WebInterfaceManager(null, null) {
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
        assertEquals(Arrays.asList("Users", "Ling"),
                openApi.getPaths().get("/user-ling/user/listUsers").getGet().getTags());
        assertTrue(openApi.getTags().stream().anyMatch(t ->
                "Users".equals(t.getName()) && "Users module from class @Tag".equals(t.getDescription())));
    }

    @Test
    @DisplayName("测试 isGlobal 注入以及不同 HTTP 方法和参数注解")
    void testGlobalCustomiseAndHttpMethods() throws Exception {
        DemoController controller = new DemoController();
        Method postMethod = DemoController.class.getMethod("createUser", String.class);
        Method arrayPostMethod = DemoController.class.getMethod("createUsers", String[].class);
        Method pathMethod = DemoController.class.getMethod("getUser", String.class);

        WebInterfaceMetadata metaPost = WebInterfaceMetadata.builder()
                .lingId("user-ling")
                .targetBean(controller)
                .targetClassName(DemoController.class.getName())
                .targetMethodName(postMethod.getName())
                .targetMethod(postMethod)
                .urlPattern("/user-ling/create")
                .httpMethod("POST")
                .build();

        WebInterfaceMetadata metaArrayPost = WebInterfaceMetadata.builder()
                .lingId("user-ling")
                .targetBean(controller)
                .targetClassName(DemoController.class.getName())
                .targetMethodName(arrayPostMethod.getName())
                .targetMethod(arrayPostMethod)
                .urlPattern("/user-ling/create-multiple")
                .httpMethod("PUT")
                .build();

        WebInterfaceMetadata metaPath = WebInterfaceMetadata.builder()
                .lingId("user-ling")
                .targetBean(controller)
                .targetClassName(DemoController.class.getName())
                .targetMethodName(pathMethod.getName())
                .targetMethod(pathMethod)
                .urlPattern("/user-ling/get/**")
                .httpMethod("DELETE")
                .build();

        Map<String, List<WebInterfaceMetadata>> map = new HashMap<>();
        map.put(metaPost.buildRouteKey(), Collections.singletonList(metaPost));
        map.put(metaArrayPost.buildRouteKey(), Collections.singletonList(metaArrayPost));
        map.put(metaPath.buildRouteKey(), Collections.singletonList(metaPath));

        WebInterfaceManager manager = new WebInterfaceManager(null, null) {
            @Override
            public Map<String, List<WebInterfaceMetadata>> getMetadataMap() {
                return map;
            }
        };

        // isGlobal 情况下，不传递 matches 参数
        LingOpenApiCustomizer customizer = new LingOpenApiCustomizer(manager);
        OpenAPI openApi = new OpenAPI();

        customizer.customise(openApi);

        assertNotNull(openApi.getPaths());
        // POST
        assertNotNull(openApi.getPaths().get("/user-ling/create").getPost());
        assertNotNull(openApi.getPaths().get("/user-ling/create").getPost().getRequestBody());

        // PUT + Array RequestBody
        assertNotNull(openApi.getPaths().get("/user-ling/create-multiple").getPut());
        assertNotNull(openApi.getPaths().get("/user-ling/create-multiple").getPut().getRequestBody());

        // DELETE + PathVariable
        assertNotNull(openApi.getPaths().get("/user-ling/get/{path}").getDelete());
        assertFalse(openApi.getPaths().get("/user-ling/get/{path}").getDelete().getParameters().isEmpty());
    }

    @Test
    @DisplayName("测试 matches 的排除与包含规则过滤")
    void testFilteringRules() throws Exception {
        DemoController controller = new DemoController();
        Method method = DemoController.class.getMethod("listUsers");

        WebInterfaceMetadata metaInclude = WebInterfaceMetadata.builder()
                .lingId("include-ling")
                .targetBean(controller)
                .targetClassName(DemoController.class.getName())
                .targetMethodName(method.getName())
                .targetMethod(method)
                .urlPattern("/include/list")
                .httpMethod("GET")
                .build();

        WebInterfaceMetadata metaExcludePath = WebInterfaceMetadata.builder()
                .lingId("exclude-path-ling")
                .targetBean(controller)
                .targetClassName(DemoController.class.getName())
                .targetMethodName(method.getName())
                .targetMethod(method)
                .urlPattern("/exclude/list")
                .httpMethod("GET")
                .build();

        Map<String, List<WebInterfaceMetadata>> map = new HashMap<>();
        map.put(metaInclude.buildRouteKey(), Collections.singletonList(metaInclude));
        map.put(metaExcludePath.buildRouteKey(), Collections.singletonList(metaExcludePath));

        WebInterfaceManager manager = new WebInterfaceManager(null, null) {
            @Override
            public Map<String, List<WebInterfaceMetadata>> getMetadataMap() {
                return map;
            }
        };

        LingOpenApiCustomizer customizer = new LingOpenApiCustomizer(manager);
        OpenAPI openApi = new OpenAPI();

        // 仅匹配 /include/**, 排除 /exclude/**, 且 package-scan 匹配 correct, package-exclude 排除 correct
        customizer.customise(openApi, 
                Arrays.asList("/include/**"), 
                Arrays.asList("com.lingframe.starter"), 
                Arrays.asList("/exclude/**"), 
                Arrays.asList("com.other.pkg"));

        assertNotNull(openApi.getPaths().get("/include/list"));
        assertNull(openApi.getPaths().get("/exclude/list"));
    }

    @Test
    @DisplayName("测试移除不匹配的现有 OpenAPI 路径")
    void testRemoveMismatchedPaths() throws Exception {
        DemoController controller = new DemoController();
        Method method = DemoController.class.getMethod("listUsers");

        WebInterfaceMetadata metaMismatch = WebInterfaceMetadata.builder()
                .lingId("mismatch-ling")
                .targetBean(controller)
                .targetClassName(DemoController.class.getName())
                .targetMethodName(method.getName())
                .targetMethod(method)
                .urlPattern("/mismatch/list")
                .httpMethod("GET")
                .build();

        WebInterfaceManager manager = new WebInterfaceManager(null, null) {
            @Override
            public Map<String, List<WebInterfaceMetadata>> getMetadataMap() {
                return Collections.singletonMap(metaMismatch.buildRouteKey(), Collections.singletonList(metaMismatch));
            }
        };

        LingOpenApiCustomizer customizer = new LingOpenApiCustomizer(manager);
        OpenAPI openApi = new OpenAPI();
        Paths paths = new Paths();
        paths.addPathItem("/mismatch/list", new PathItem());
        openApi.setPaths(paths);

        // 包含路径仅匹配 /match/**，因此 /mismatch/list 会被移除
        customizer.customise(openApi, Arrays.asList("/match/**"));

        assertTrue(openApi.getPaths().isEmpty());
    }

    static class DemoController {
        public String listUsers() {
            return "ok";
        }

        public String createUser(@RequestBody String user) {
            return "ok";
        }

        public String createUsers(@RequestBody String[] users) {
            return "ok";
        }

        public String getUser(@PathVariable String path) {
            return "ok";
        }
    }
}
