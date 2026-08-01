package com.lingframe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationExecutionMode;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.starter.governance.EntryInvocationGovernanceResolver;
import com.lingframe.starter.web.WebGovernanceSupport;
import com.lingframe.starter.web.WebInterfaceManager;
import com.lingframe.starter.web.WebRequestFacade;
import com.lingframe.starter.web.WebRouteResolution;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
        classes = ObservabilityTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named = "lingframe.runE2E", matches = "true")
@DisplayName("观测闭环集成回归")
class ObservabilityClosedLoopIntegrationTest {

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

    @Autowired
    private MetricsCollector metricsCollector;

    @Autowired
    private InvocationPipelineEngine pipelineEngine;

    @Autowired
    private EntryInvocationGovernanceResolver invocationGovernanceResolver;

    @Autowired
    private WebInterfaceManager webInterfaceManager;

    private String lastGovernanceBurstDiagnostics = "";
    private static final WebGovernanceSupport WEB_GOVERNANCE_SUPPORT = new WebGovernanceSupport();

    @Test
    @DisplayName("应覆盖真实请求后的健康指标、治理信号与卸载预检结果")
    void shouldCoverHealthGovernanceAndUninstallPrecheck() throws Exception {
        JsonNode lings = waitForLingsLoaded();
        JsonNode userLing = findLing(lings, "user-ling");
        assertNotNull(userLing, "user-ling should be loaded");

        for (int i = 0; i < 3; i++) {
            JsonNode response = getJson("/user-ling/user/listUsers");
            assertTrue(response.isArray(), "user list response should be array");
        }

        assertTrue(waitForCollectorMetric(), diagnosticSnapshot());

        JsonNode healthAll = waitForCondition("/lingframe/dashboard/metrics/lings/health/all", root -> {
            JsonNode summary = root.path("data").path("user-ling").path("summary");
            return summary.path("totalRequests").asLong(0) >= 3;
        });

        JsonNode userHealth = healthAll.path("data").path("user-ling");
        assertTrue(userHealth.path("summary").path("totalRequests").asLong() >= 3);
        assertTrue(userHealth.path("versions").fields().hasNext(), "version metrics should not be empty");

        String canaryVersion = findCanaryVersion(userLing);
        assertNotNull(canaryVersion, "user-ling canary version should exist");

        postJson(
                "/lingframe/dashboard/governance/user-ling/invocation",
                "{\"timeoutMs\":3000,\"rateLimitPerSecond\":1,\"maxConcurrentThreads\":1}"
        );

        triggerGovernedHttpBurst();
        int rejected = triggerGovernanceEntryBurst(canaryVersion);
        assertTrue(rejected > 0, "expected at least one rate-limited request, diagnostics=" + lastGovernanceBurstDiagnostics);

        JsonNode governanceAll = waitForCondition("/lingframe/dashboard/metrics/lings/governance/all", root -> {
            JsonNode summary = root.path("data").path("user-ling").path("summary");
            return totalGovernanceSignals(summary) > 0;
        });

        JsonNode userGovernance = governanceAll.path("data").path("user-ling");
        assertTrue(totalGovernanceSignals(userGovernance.path("summary")) > 0);

        JsonNode uninstall = deleteJson("/lingframe/dashboard/lings/uninstall/user-ling/" + canaryVersion);
        JsonNode uninstallData = uninstall.path("data");
        assertTrue(uninstall.path("success").asBoolean(), "uninstall api should succeed");
        assertEquals("user-ling", uninstallData.path("lingId").asText());
        assertEquals(canaryVersion, uninstallData.path("version").asText());
        assertTrue(uninstallData.has("overallRiskLevel"));
        assertTrue(uninstallData.has("uninstallTriggered"));
    }

    private JsonNode waitForLingsLoaded() throws Exception {
        return waitForCondition("/lingframe/dashboard/lings", root -> {
            JsonNode data = root.path("data");
            JsonNode userLing = findLing(data, "user-ling");
            return data.isArray() && data.size() >= 2 && userLing != null && findCanaryVersion(userLing) != null;
        }).path("data");
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

    private boolean waitForCollectorMetric() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            if (!metricsCollector.getAllMetrics().isEmpty()
                    || metricsCollector.getSnapshot("user-ling").getTotalRequests() > 0
                    || !metricsCollector.getVersionSnapshots("user-ling").isEmpty()) {
                return true;
            }
            Thread.sleep(250L);
        }
        return false;
    }

    private String diagnosticSnapshot() {
        return "metrics=" + metricsCollector.getAllMetrics().keySet()
                + ", summary=" + metricsCollector.getSnapshot("user-ling")
                + ", versions=" + metricsCollector.getVersionSnapshots("user-ling").keySet();
    }

    private void triggerGovernedHttpBurst() throws Exception {
        int concurrency = 8;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        return null;
                    }
                    restTemplate.getForEntity(url("/user-ling/user/listUsers"), String.class);
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "burst workers should be ready");
            start.countDown();

            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private int triggerGovernanceEntryBurst(String version) throws Exception {
        int concurrency = 8;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        Map<String, Integer> kinds = new ConcurrentHashMap<>();
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        return false;
                    }
                    InvocationContext ctx = InvocationContext.obtain();
                    try {
                        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/user-ling/user/listUsers");
                        WebRouteResolution resolution = webInterfaceManager.resolveRoute(request);
                        ctx.copyFrom(buildGovernedWebEntryContext(resolution, request));
                        ctx.setCallerLingId("user-ling");
                        ctx.setTargetVersion(version);
                        pipelineEngine.invoke(ctx);
                        return false;
                    } catch (Exception ex) {
                        String kind = ex.getClass().getSimpleName();
                        if (ex instanceof LingInvocationException) {
                            LingInvocationException invocationException = (LingInvocationException) ex;
                            Throwable cause = invocationException.getCause();
                            kind = invocationException.getKind().name()
                                    + (cause == null ? "" : ":" + cause.getClass().getSimpleName()
                                    + ":" + cause.getMessage());
                        }
                        kinds.merge(kind, 1, Integer::sum);
                        return ex instanceof LingInvocationException
                                && ((LingInvocationException) ex).getKind() == LingInvocationException.ErrorKind.RATE_LIMITED;
                    } finally {
                        ctx.recycle();
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "governance workers should be ready");
            start.countDown();

            int rejected = 0;
            for (Future<Boolean> future : futures) {
                if (Boolean.TRUE.equals(future.get(10, TimeUnit.SECONDS))) {
                    rejected++;
                }
            }
            lastGovernanceBurstDiagnostics = kinds.toString();
            return rejected;
        } finally {
            executor.shutdownNow();
        }
    }

    private InvocationContext buildGovernedWebEntryContext(WebRouteResolution resolution, MockHttpServletRequest request) {
        InvocationContext ctx = WEB_GOVERNANCE_SUPPORT.buildInvocationContext(
                new MockWebRequestFacade(request),
                WEB_GOVERNANCE_SUPPORT.resolveGovernedMethod(true, resolution.getMetadata(), null, "user-ling"),
                "user-ling",
                resolution.getMetadata(),
                invocationGovernanceResolver);
        ctx.execution().setMode(InvocationExecutionMode.GOVERN_ONLY);
        WEB_GOVERNANCE_SUPPORT.preResolveLingTarget(ctx, resolution);
        return ctx;
    }

    private static final class MockWebRequestFacade implements WebRequestFacade {
        private final MockHttpServletRequest request;

        private MockWebRequestFacade(MockHttpServletRequest request) {
            this.request = request;
        }

        @Override
        public String getMethod() {
            return request.getMethod();
        }

        @Override
        public String getRequestURI() {
            return request.getRequestURI();
        }

        @Override
        public String getHeader(String name) {
            return request.getHeader(name);
        }

        @Override
        public Principal getUserPrincipal() {
            return request.getUserPrincipal();
        }

        @Override
        public String getRemoteUser() {
            return request.getRemoteUser();
        }
    }

    private long totalGovernanceSignals(JsonNode summary) {
        return summary.path("rateLimitedRequests").asLong(0)
                + summary.path("bulkheadRejectedRequests").asLong(0)
                + summary.path("timeoutRequests").asLong(0)
                + summary.path("circuitOpenRejections").asLong(0)
                + summary.path("circuitOpenedCount").asLong(0)
                + summary.path("recoveryCount").asLong(0);
    }

    private static final String ACCESS_TOKEN = "lingframe";

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
        assertEquals(HttpStatus.OK, response.getStatusCode(), "POST " + path + " should return 200, body was: " + response.getBody());
        return objectMapper.readTree(response.getBody());
    }

    private JsonNode deleteJson(String path) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Access-Token", ACCESS_TOKEN);
        headers.set("Origin", "http://localhost:" + port);
        ResponseEntity<String> response = restTemplate.exchange(url(path), HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "DELETE " + path + " should return 200, body was: " + response.getBody());
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

    private String findCanaryVersion(JsonNode ling) {
        Iterator<JsonNode> iterator = ling.path("versionDetails").elements();
        while (iterator.hasNext()) {
            JsonNode version = iterator.next();
            if (!version.path("isDefault").asBoolean(false)) {
                return version.path("version").asText(null);
            }
        }
        return null;
    }
}
