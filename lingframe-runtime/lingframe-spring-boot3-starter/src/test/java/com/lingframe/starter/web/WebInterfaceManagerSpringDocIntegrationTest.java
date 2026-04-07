package com.lingframe.starter.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.starter.configuration.LingFrameAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.time.Duration;
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
@DisplayName("WebInterfaceManager SpringDoc SB3 集成测试")
@Disabled("SpringDoc + Boot3 test classpath is unstable in module isolation; grouped-doc coverage lives in LingOpenApiCustomizerTest")
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
    @DisplayName("分组 SpringDoc 应暴露动态灵元端点")
    void shouldExposeDynamicLingEndpointInGroupedSpringDoc() throws Exception {
        HandlerMethod handlerMethod = awaitHandlerMethod(LING_PATH);
        assertNotNull(handlerMethod);
        assertTrue(handlerMethod.getBean() instanceof WebInterfaceManager.LingWebEntryHandler);
        assertEquals("dispatch", handlerMethod.getMethod().getName());

        JsonNode groupedDoc = awaitApiDoc("/v3/api-docs/lings", LING_PATH);
        JsonNode operation = groupedDoc.path("paths").path(LING_PATH).path("get");
        assertFalse(operation.isMissingNode());
        assertEquals("列出灵元用户", operation.path("summary").asText());
    }

    @Test
    @DisplayName("swagger-config 应暴露 lings 分组文档入口")
    void shouldExposeLingGroupInSwaggerConfig() throws Exception {
        awaitHandlerMethod(LING_PATH);

        JsonNode swaggerConfig = awaitSwaggerConfig("Lings", "/v3/api-docs/lings");
        JsonNode urls = swaggerConfig.path("urls");
        assertTrue(urls.isArray());

        boolean found = false;
        for (JsonNode url : urls) {
            if ("Lings".equals(url.path("name").asText())
                    && "/v3/api-docs/lings".equals(url.path("url").asText())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Swagger UI config should contain grouped ling docs");
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
            if (response.getStatusCodeValue() / 100 == 2 && lastBody != null) {
                JsonNode document = objectMapper.readTree(lastBody);
                if (document.path("paths").has(expectedPath)) {
                    return document;
                }
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new AssertionError("SpringDoc missing path " + expectedPath + ": " + abbreviate(lastBody));
    }

    private JsonNode awaitSwaggerConfig(String expectedName, String expectedUrl) throws Exception {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        String lastBody = null;
        while (System.nanoTime() < deadline) {
            ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs/swagger-config", String.class);
            lastBody = response.getBody();
            if (response.getStatusCodeValue() / 100 == 2 && lastBody != null) {
                JsonNode document = objectMapper.readTree(lastBody);
                JsonNode urls = document.path("urls");
                if (urls.isArray()) {
                    for (JsonNode url : urls) {
                        if (expectedName.equals(url.path("name").asText())
                                && expectedUrl.equals(url.path("url").asText())) {
                            return document;
                        }
                    }
                }
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new AssertionError("Swagger config missing group " + expectedName + ": " + abbreviate(lastBody));
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractPatterns(RequestMappingInfo mappingInfo) {
        try {
            Method method = RequestMappingInfo.class.getMethod("getPatternValues");
            return (Set<String>) method.invoke(mappingInfo);
        } catch (Exception ex) {
            return java.util.Collections.emptySet();
        }
    }

    private String abbreviate(String body) {
        if (body == null) {
            return "<null>";
        }
        return body.length() <= 400 ? body : body.substring(0, 400) + "...";
    }

    @SpringBootApplication
    @ImportAutoConfiguration(LingFrameAutoConfiguration.class)
    @org.springframework.context.annotation.Import(TestConfig.class)
    static class TestApplication {
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @Bean
        DemoLingController demoLingController() {
            return new DemoLingController();
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
                        .opSummary("列出灵元用户")
                        .build();
                manager.registerSync(metadata);
            };
        }
    }

    @RestController
    static class DemoLingController {
        public List<String> listUsers() {
            return java.util.Collections.singletonList("alice");
        }
    }
}
