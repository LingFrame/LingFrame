package com.lingframe.starter.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.OpenAPIService;
import org.springdoc.core.providers.SpringWebProvider;
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
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMethod;
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
        classes = WebInterfaceManagerSpringDocDetachedBeanIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.port=0",
                "spring.mvc.pathmatch.matching-strategy=ant_path_matcher",
                "springdoc.cache.disabled=true"
        }
)
@DisplayName("WebInterfaceManager SpringDoc 脱离宿主 Bean 集成测试")
class WebInterfaceManagerSpringDocDetachedBeanIntegrationTest {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final long POLL_INTERVAL_MILLIS = 250L;
    private static final String LING_PATH = "/detached-ling/demo/list";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private OpenAPIService openAPIService;

    @Autowired(required = false)
    private SpringWebProvider springWebProvider;

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    @Test
    @DisplayName("脱离宿主 Spring Bean 的灵元接口也应出现在 SpringDoc 中")
    void shouldExposeDetachedLingEndpointInSpringDoc() throws Exception {
        HandlerMethod handlerMethod = awaitHandlerMethod(LING_PATH);
        assertNotNull(handlerMethod);
        assertEquals(DetachedLingController.class.getName(), handlerMethod.getBeanType().getName());
        assertEquals("list", handlerMethod.getMethod().getName());
        assertNotNull(openAPIService);
        assertNotNull(springWebProvider);
        assertTrue(containsHandler(springWebProvider.getHandlerMethods(), LING_PATH),
                () -> "SpringWebProvider handlerMethods 缺少路径，当前映射: " + summarizeSpringDocHandlers());
        assertTrue(isSpringDocRestController(handlerMethod, LING_PATH),
                () -> "SpringDoc isRestController 判定失败，bean=" + handlerMethod.getBean()
                        + ", mappingsKeys=" + openAPIService.getMappingsMap().keySet());

        JsonNode document = awaitApiDoc("/v3/api-docs", LING_PATH);
        JsonNode operation = document.path("paths").path(LING_PATH).path("get");
        assertFalse(operation.isMissingNode());
        assertEquals("列出脱离宿主的灵元数据", operation.path("summary").asText());
        assertTrue(containsText(operation.path("tags"), "Detached Ling"));
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
                if (pattern.contains("detached-ling")) {
                    routes.add(pattern + " -> " + entry.getValue().getBeanType().getName()
                            + "#" + entry.getValue().getMethod().getName());
                }
            }
        }
        return routes;
    }

    private boolean containsHandler(Map<RequestMappingInfo, HandlerMethod> handlerMethods, String path) {
        if (handlerMethods == null || handlerMethods.isEmpty()) {
            return false;
        }
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            if (extractPatterns(entry.getKey()).contains(path)) {
                return true;
            }
        }
        return false;
    }

    private List<String> summarizeSpringDocHandlers() {
        List<String> routes = new ArrayList<>();
        if (springWebProvider == null) {
            return routes;
        }
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = springWebProvider.getHandlerMethods();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
            for (String pattern : extractPatterns(entry.getKey())) {
                if (pattern.contains("detached-ling")) {
                    routes.add(pattern + " -> " + entry.getValue().getBeanType().getName()
                            + "#" + entry.getValue().getMethod().getName()
                            + " [bean=" + entry.getValue().getBean() + "]");
                }
            }
        }
        return routes;
    }

    @SuppressWarnings("unchecked")
    private boolean isSpringDocRestController(HandlerMethod handlerMethod, String path) throws Exception {
        ClassLoader loader = applicationContext.getClassLoader();
        if (loader == null) {
            loader = getClass().getClassLoader();
        }
        Class<?> abstractOpenApiResourceClass = Class.forName("org.springdoc.api.AbstractOpenApiResource", false, loader);
        Map<String, Object> resources = (Map<String, Object>)
                applicationContext.getBeansOfType((Class<Object>) abstractOpenApiResourceClass);
        Object resource = resources.values().stream().findFirst().orElse(null);
        if (resource == null) {
            return false;
        }
        Method method = ReflectionUtils.findMethod(
                abstractOpenApiResourceClass, "isRestController", Map.class, HandlerMethod.class, String.class);
        if (method == null) {
            return false;
        }
        ReflectionUtils.makeAccessible(method);
        Object result = method.invoke(resource, openAPIService.getMappingsMap(), handlerMethod, path);
        return Boolean.TRUE.equals(result);
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
        WebInterfaceManager webInterfaceManager(RequestMappingHandlerMapping mapping,
                                                RequestMappingHandlerAdapter adapter,
                                                ConfigurableApplicationContext context) {
            WebInterfaceManager manager = new WebInterfaceManager(null, null);
            manager.init(mapping, adapter, context);
            return manager;
        }

        @Bean
        ApplicationRunner detachedLingRouteRegistrar(WebInterfaceManager manager,
                                                     ConfigurableApplicationContext context) {
            return args -> {
                DetachedLingController controller = new DetachedLingController();
                Method targetMethod = DetachedLingController.class.getMethod("list");
                RequestMappingInfo mappingInfo = RequestMappingInfo.paths(LING_PATH)
                        .methods(RequestMethod.GET)
                        .build();

                WebInterfaceMetadata metadata = WebInterfaceMetadata.builder()
                        .lingId("detached-ling")
                        .version("1.0.0")
                        .targetBeanName("detachedLingController")
                        .targetBean(controller)
                        .targetClassName(DetachedLingController.class.getName())
                        .targetMethodName(targetMethod.getName())
                        .targetMethodParameterTypeNames(new String[0])
                        .targetMethod(targetMethod)
                        .classLoader(DetachedLingController.class.getClassLoader())
                        .lingApplicationContext(context)
                        .urlPattern(LING_PATH)
                        .httpMethod("GET")
                        .requestMappingInfo(mappingInfo)
                        .build();
                manager.registerSync(metadata);
            };
        }
    }

    @Tag(name = "Detached Ling", description = "脱离宿主 Bean 的灵元接口")
    static class DetachedLingController {

        @Operation(summary = "列出脱离宿主的灵元数据")
        public List<String> list() {
            return java.util.Collections.singletonList("detached");
        }
    }
}
