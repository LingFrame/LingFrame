package com.lingframe.core.router;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.spi.LingContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class LabelMatchRouterTest {

    private LabelMatchRouter router;

    @BeforeEach
    void setUp() {
        router = new LabelMatchRouter();
    }

    private LingInstance createInstance(String version, Map<String, String> labels, Map<String, Object> properties) {
        LingContainer container = mock(LingContainer.class);
        LingDefinition definition = new LingDefinition();
        definition.setId("test-service");
        definition.setVersion(version);
        definition.setProperties(properties != null ? properties : Collections.emptyMap());
        LingInstance instance = new LingInstance(container, definition);
        if (labels != null) {
            instance.addLabels(labels);
        }
        return instance;
    }

    @Test
    @DisplayName("标签匹配测试：应选择匹配分数最高的实例")
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

        // 请求标签：env=prod, region=sh
        Map<String, String> requestLabels = new HashMap<>();
        requestLabels.put("env", "prod");
        requestLabels.put("region", "sh");

        InvocationContext context = InvocationContext.builder()
                .labels(requestLabels)
                .build();

        LingInstance selected = router.route(candidates, context);
        assertEquals("1.0.1", selected.getVersion(), "应该匹配到北京之外的上海实例");

        // 请求标签：env=prod, region=bj
        requestLabels.put("region", "bj");
        selected = router.route(candidates, context);
        assertEquals("1.0.2", selected.getVersion());
    }

    @Test
    @DisplayName("标签不匹配测试：如果实例有标签但值不对，应过滤掉")
    void testLabelMismatch() {
        Map<String, String> labels1 = Collections.singletonMap("version", "v1");
        LingInstance inst1 = createInstance("1.0.1", labels1, null);

        Map<String, String> labels2 = Collections.singletonMap("version", "v2");
        LingInstance inst2 = createInstance("1.0.2", labels2, null);

        List<LingInstance> candidates = Arrays.asList(inst1, inst2);

        InvocationContext context = InvocationContext.builder()
                .labels(Collections.singletonMap("version", "v3")) // 没有匹配的
                .build();

        LingInstance selected = router.route(candidates, context);
        // 如果没有匹配的，逻辑是 orElse(candidates.get(0))
        assertEquals("1.0.1", selected.getVersion());
    }

    @Test
    @DisplayName("权重路由测试：无标签时应按 trafficWeight 随机选择")
    void testWeightedRouting() {
        // 实例 1：权重 80
        LingInstance inst1 = createInstance("1.0.1", null, Collections.singletonMap("trafficWeight", 80));
        // 实例 2：权重 20
        LingInstance inst2 = createInstance("1.0.2", null, Collections.singletonMap("trafficWeight", 20));

        List<LingInstance> candidates = Arrays.asList(inst1, inst2);
        InvocationContext context = InvocationContext.builder().build();

        int count1 = 0;
        int count2 = 0;
        for (int i = 0; i < 1000; i++) {
            LingInstance selected = router.route(candidates, context);
            if ("1.0.1".equals(selected.getVersion()))
                count1++;
            else
                count2++;
        }

        // 验证比例（大致 4:1）
        assertTrue(count1 > 700, "权重 80 的实例被选中次数应较多: " + count1);
        assertTrue(count2 > 100, "权重 20 的实例也应被选中一些: " + count2);
    }
}
