package com.lingframe.core.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 路由事实快照测试。 */
@DisplayName("结构化路由事实")
class RoutingDecisionTest {
    @Test
    @DisplayName("保留版本、实例、修订和选路原因")
    void keepsFinalFacts() {
        RoutingDecision decision = new RoutingDecision("a", "v2", "a@v2#1", "r1", "weights");
        assertEquals("a", decision.getLingId());
        assertEquals("v2", decision.getVersion());
        assertEquals("a@v2#1", decision.getInstanceId());
        assertEquals("r1", decision.getPolicyRevision());
        assertEquals("weights", decision.getReason());
    }

    @Test
    @DisplayName("关键路由身份不能为空")
    void rejectsMissingIdentity() {
        assertThrows(NullPointerException.class,
                () -> new RoutingDecision(null, "v1", "a@v1#1", null, "weights"));
        assertThrows(NullPointerException.class,
                () -> new RoutingDecision("a", "v1", null, null, "weights"));
    }
}
