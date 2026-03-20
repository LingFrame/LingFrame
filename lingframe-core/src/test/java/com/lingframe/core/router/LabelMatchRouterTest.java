package com.lingframe.core.router;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.spi.LingContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("LabelMatchRouter 测试")
class LabelMatchRouterTest {

    private LabelMatchRouter router;

    @BeforeEach
    void setUp() {
        router = new LabelMatchRouter();
    }

    @AfterEach
    void tearDown() {
        InvocationContext.obtain().reset();
    }

    @Nested
    @DisplayName("标签匹配")
    class LabelMatchingTests {

        @Test
        @DisplayName("应优先选择标签匹配分数最高的实例")
        void testLabelMatching() {
            Map<String, String> labels1 = new HashMap<>();
            labels1.put("env", "prod");
            labels1.put("region", "sh");
            LingInstance inst1 = createInstance("1.0.1", labels1, null);

            Map<String, String> labels2 = new HashMap<>();
            labels2.put("env", "prod");
            labels2.put("region", "bj");
            LingInstance inst2 = createInstance("1.0.2", labels2, null);

            List<LingInstance> candidates = Arrays.asList(inst1, inst2);

            Map<String, String> requestLabels = new HashMap<>();
            requestLabels.put("env", "prod");
            requestLabels.put("region", "sh");

            InvocationContext context = InvocationContext.obtain();
            context.setLabels(requestLabels);

            LingInstance selected = router.route(candidates, context);
            assertEquals("1.0.1", selected.getVersion(), "应优先匹配上海实例");

            requestLabels.put("region", "bj");
            selected = router.route(candidates, context);
            assertEquals("1.0.2", selected.getVersion());
        }

        @Test
        @DisplayName("请求标签不匹配时应过滤掉有冲突标签的实例")
        void testLabelMismatch() {
            Map<String, String> labels1 = Collections.singletonMap("version", "v1");
            LingInstance inst1 = createInstance("1.0.1", labels1, null);

            Map<String, String> labels2 = Collections.singletonMap("version", "v2");
            LingInstance inst2 = createInstance("1.0.2", labels2, null);

            List<LingInstance> candidates = Arrays.asList(inst1, inst2);

            InvocationContext context = InvocationContext.obtain();
            context.setLabels(Collections.singletonMap("version", "v3"));

            LingInstance selected = router.route(candidates, context);
            assertEquals("1.0.1", selected.getVersion());
        }
    }

    @Nested
    @DisplayName("权重路由")
    class WeightedRoutingTests {

        @Test
        @DisplayName("无标签时应按 trafficWeight 概率选择实例")
        void testWeightedRouting() {
            LingInstance inst1 = createInstance("1.0.1", null, Collections.singletonMap("trafficWeight", 80));
            LingInstance inst2 = createInstance("1.0.2", null, Collections.singletonMap("trafficWeight", 20));

            List<LingInstance> candidates = Arrays.asList(inst1, inst2);
            InvocationContext context = InvocationContext.obtain();

            int count1 = 0;
            int count2 = 0;
            for (int i = 0; i < 1000; i++) {
                LingInstance selected = router.route(candidates, context);
                if ("1.0.1".equals(selected.getVersion())) {
                    count1++;
                } else {
                    count2++;
                }
            }

            assertTrue(count1 > 700, "权重 80 的实例应明显更高频被选中: " + count1);
            assertTrue(count2 > 100, "权重 20 的实例也应被选中一定次数: " + count2);
        }
    }

    private LingInstance createInstance(String version, Map<String, String> labels, Map<String, Object> properties) {
        LingContainer container = mock(LingContainer.class);
        LingDefinition definition = new LingDefinition();
        definition.setId("test-service");
        definition.setVersion(version);
        definition.setProperties(properties != null ? properties : Collections.emptyMap());
        LingInstance instance = new LingInstance(container, definition, new EventBus());
        if (labels != null) {
            instance.addLabels(labels);
        }
        return instance;
    }
}
