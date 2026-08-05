package com.lingframe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(
        classes = ObservabilityTestApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:lingframe_dashboard_ui;DB_CLOSE_DELAY=0;MODE=MySQL"
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named = "lingframe.runE2E", matches = "true")
@DisplayName("Dashboard 后端 API 集成与冒烟回归")
class DashboardUiSmokeIntegrationTest {

    @BeforeAll
    static void setupClass() {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String ACCESS_TOKEN = "lingframe";

    @Test
    @DisplayName("应覆盖 Dashboard 静态页面可达性、灵元状态机切换与治理持久化保存")
    void shouldCoverDashboardApiLifecycleAndGovernance() throws Exception {
        // 1. 验证静态 HTML 资源可访问（证明 Dashboard Web 资源映射正常）
        ResponseEntity<String> htmlResponse = restTemplate.getForEntity(url("/dashboard.html"), String.class);
        assertEquals(HttpStatus.OK, htmlResponse.getStatusCode(), "dashboard.html should be accessible");
        assertTrue(htmlResponse.getBody() != null && htmlResponse.getBody().contains("id=\"app\""),
                "dashboard.html should contain app div mountpoint, body was: " + htmlResponse.getBody());

        // 2. 轮询等待 user-ling 灵元加载并初始化为 ACTIVE
        JsonNode lingsData = waitForCondition("/lingframe/dashboard/lings", root -> {
            JsonNode data = root.path("data");
            return data.isArray() && data.size() >= 2 && findLing(data, "user-ling") != null;
        }).path("data");

        JsonNode userLing = findLing(lingsData, "user-ling");
        assertNotNull(userLing, "user-ling should be loaded");
        assertEquals("ACTIVE", userLing.path("status").asText(), "user-ling should default to ACTIVE");

        // 3. 状态切换测试：切换 user-ling 为 INACTIVE
        postJson("/lingframe/dashboard/lings/user-ling/status", "{\"status\":\"INACTIVE\"}");
        waitForCondition("/lingframe/dashboard/lings", root -> {
            JsonNode user = findLing(root.path("data"), "user-ling");
            return user != null && "INACTIVE".equals(user.path("status").asText());
        });

        // 4. 状态切换恢复：恢复 user-ling 为 ACTIVE
        postJson("/lingframe/dashboard/lings/user-ling/status", "{\"status\":\"ACTIVE\"}");
        waitForCondition("/lingframe/dashboard/lings", root -> {
            JsonNode user = findLing(root.path("data"), "user-ling");
            return user != null && "ACTIVE".equals(user.path("status").asText());
        });

        // 5. 治理链配置验证：写入限流超时配置
        String governanceJson = "{\"timeoutMs\":1600,\"rateLimitPerSecond\":5,\"maxConcurrentThreads\":2,\"retryCount\":1,\"fallbackValue\":\"ui-fallback\"}";
        postJson("/lingframe/dashboard/governance/user-ling/invocation", governanceJson);

        // 6. 治理配置持久化回读验证
        waitForCondition("/lingframe/dashboard/governance/user-ling/invocation", root -> {
            JsonNode data = root.path("data");
            return data.path("timeoutMs").asInt() == 1600
                    && data.path("rateLimitPerSecond").asInt() == 5
                    && data.path("maxConcurrentThreads").asInt() == 2
                    && data.path("retryCount").asInt() == 1
                    && "ui-fallback".equals(data.path("fallbackValue").asText());
        });
    }

    private JsonNode waitForCondition(String path, Predicate<JsonNode> predicate) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(25));
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            last = getJson(path);
            if (predicate.test(last)) {
                return last;
            }
            Thread.sleep(500L);
        }
        fail("Condition not met for path: " + path + ", last response: " + (last == null ? "<null>" : last.toPrettyString()));
        return null;
    }

    private JsonNode getJson(String path) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Access-Token", ACCESS_TOKEN);
        ResponseEntity<String> response = restTemplate.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "GET " + path + " should return 200");
        return objectMapper.readTree(response.getBody());
    }

    private JsonNode postJson(String path, String body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Access-Token", ACCESS_TOKEN);
        headers.set("Origin", "http://localhost:" + port);
        ResponseEntity<String> response = restTemplate.postForEntity(url(path), new HttpEntity<>(body, headers), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "POST " + path + " should return 200, body: " + response.getBody());
        return objectMapper.readTree(response.getBody());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private JsonNode findLing(JsonNode data, String lingId) {
        if (data == null || !data.isArray()) {
            return null;
        }
        for (JsonNode ling : data) {
            if (lingId.equals(ling.path("lingId").asText())) {
                return ling;
            }
        }
        return null;
    }
}
