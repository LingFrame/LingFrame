package com.lingframe.starter.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = WebInterfaceManagerSpringDocIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.port=0",
                "spring.mvc.pathmatch.matching-strategy=ant_path_matcher",
                "springdoc.cache.disabled=true",
                "springdoc.group-configs[0].group=lings",
                "springdoc.group-configs[0].display-name=Lings",
                "springdoc.group-configs[0].paths-to-match=/**-ling/**"
        }
)
@DisplayName("WebInterfaceManager SpringDoc 集成测试")
class WebInterfaceManagerSpringDocIntegrationTest {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final long POLL_INTERVAL_MILLIS = 250L;
    private static final String LING_PATH = "/user-ling/user/listUsers";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("启动后动态注册的灵元接口应出现在 SpringDoc 中")
    void shouldExposeDynamicLingEndpointInSpringDoc() throws Exception {
        HandlerMethod handlerMethod = awaitHandlerMethod(LING_PATH);
        assertNotNull(handlerMethod);
        assertEquals(DemoLingController.class.getName(), handlerMethod.getBeanType().getName());
        assertEquals("listUsers", handlerMethod.getMethod().getName());

        JsonNode defaultDoc = awaitApiDoc("/v3/api-docs", LING_PATH);
        assertTrue(defaultDoc.path("paths").has(LING_PATH));

        JsonNode groupedDoc = awaitApiDoc("/v3/api-docs/lings", LING_PATH);
        JsonNode operation = groupedDoc.path("paths").path(LING_PATH).path("get");
        assertFalse(operation.isMissingNode());
        assertEquals("列出灵元用户", operation.path("summary").asText());
        assertTrue(containsText(groupedDoc.path("tags"), "Ling Users"));
    }

    private HandlerMethod awaitHandlerMethod(String path) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        HandlerMethod lastMatch = null;
        while (System.nanoTime() < deadline) {
            lastMatch = findHandlerMethod(path);
            if (lastMatch != null) {
                return lastMatch;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        return lastMatch;
    }

    private HandlerMethod findHandlerMethod(String path) {
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            if (extractPatterns(entry.getKey()).contains(path)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private JsonNode awaitApiDoc(String uri, String expectedPath) throws Exception {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        String lastBody = null;
        while (System.nanoTime() < deadline) {
            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            lastBody = response.getBody();
            if (response.getStatusCode().is2xxSuccessful() && lastBody != null) {
                JsonNode document = objectMapper.readTree(lastBody);
                if (document.path("paths").has(expectedPath)) {
                    return document;
                }
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new AssertionError("SpringDoc 中未找到路径 " + expectedPath
                + "，当前映射：" + summarizeMappings()
                + "，最后一次响应：" + abbreviate(lastBody));
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractPatterns(RequestMappingInfo mappingInfo) {
        try {
            Method method = RequestMappingInfo.class.getMethod("getPatternValues");
            Object values = method.invoke(mappingInfo);
            if (values instanceof Set) {
                return (Set<String>) values;
            }
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Method conditionMethod = RequestMappingInfo.class.getMethod("getPatternsCondition");
            Object patternsCondition = conditionMethod.invoke(mappingInfo);
            if (patternsCondition == null) {
                return java.util.Collections.emptySet();
            }
            Method patternsMethod = patternsCondition.getClass().getMethod("getPatterns");
            Object patterns = patternsMethod.invoke(patternsCondition);
            if (patterns instanceof Set) {
                return (Set<String>) patterns;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return java.util.Collections.emptySet();
    }

    private List<String> summarizeMappings() {
        List<String> routes = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            for (String pattern : extractPatterns(entry.getKey())) {
                if (pattern.contains("-ling")) {
                    routes.add(pattern + " -> " + entry.getValue().getBeanType().getName()
                            + "#" + entry.getValue().getMethod().getName());
                }
            }
        }
        return routes;
    }

    private boolean containsText(JsonNode node, String expectedText) {
        if (node == null || node.isMissingNode()) {
            return false;
        }
        if (node.isTextual()) {
            return expectedText.equals(node.asText());
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (containsText(item, expectedText)) {
                    return true;
                }
            }
        }
        if (node.isObject()) {
            return containsText(node.path("name"), expectedText);
        }
        return false;
    }

    private String abbreviate(String body) {
        if (body == null) {
            return "<null>";
        }
        return body.length() <= 400 ? body : body.substring(0, 400) + "...";
    }

    @SpringBootApplication
    @Import(TestConfig.class)
    static class TestApplication {
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        DemoLingController demoLingController() {
            return new DemoLingController();
        }

        @Bean
        WebInterfaceManager webInterfaceManager(RequestMappingHandlerMapping mapping,
                                                RequestMappingHandlerAdapter adapter,
                                                ConfigurableApplicationContext context) {
            WebInterfaceManager manager = new WebInterfaceManager(null, null);
            manager.init(mapping, adapter, context);
            return manager;
        }

        @Bean
        ApplicationRunner lingRouteRegistrar(WebInterfaceManager manager,
                                             DemoLingController controller,
                                             ConfigurableApplicationContext context) {
            return args -> {
                Method targetMethod = DemoLingController.class.getMethod("listUsers");
                RequestMappingInfo mappingInfo = RequestMappingInfo.paths(LING_PATH)
                        .methods(RequestMethod.GET)
                        .build();

                WebInterfaceMetadata metadata = WebInterfaceMetadata.builder()
                        .lingId("user-ling")
                        .version("1.0.0")
                        .targetBeanName("demoLingController")
                        .targetBean(controller)
                        .targetClassName(DemoLingController.class.getName())
                        .targetMethodName(targetMethod.getName())
                        .targetMethodParameterTypeNames(new String[0])
                        .targetMethod(targetMethod)
                        .classLoader(DemoLingController.class.getClassLoader())
                        .lingApplicationContext(context)
                        .urlPattern(LING_PATH)
                        .httpMethod("GET")
                        .requestMappingInfo(mappingInfo)
                        .build();
                manager.registerSync(metadata);
            };
        }
    }

    @Tag(name = "Ling Users", description = "灵元用户接口")
    @RestController
    static class DemoLingController {

        @Operation(summary = "列出灵元用户")
        public List<String> listUsers() {
            return java.util.Collections.singletonList("alice");
        }
    }
}
