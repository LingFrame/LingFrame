package com.lingframe.core.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 完整版本策略的快照与配置状态测试。 */
@DisplayName("限定灵元版本策略快照")
class LingVersionPolicyTest {
    private final LingRoutingScope scope = new LingRoutingScope("a", "execute");

    @Test
    @DisplayName("版本表不可变且完整保留零权重")
    void immutableVersionTable() {
        Map<String, Integer> weights = new HashMap<>();
        weights.put("v1", 100);
        weights.put("v2", 0);
        LingVersionPolicy policy = new LingVersionPolicy(scope, "revision", true, weights);
        weights.clear();
        assertSame(scope, policy.getScope());
        assertEquals("revision", policy.getRevision());
        assertTrue(policy.isConfigured());
        assertEquals(100, policy.getVersionWeights().get("v1"));
        assertEquals(0, policy.getVersionWeights().get("v2"));
        assertThrows(UnsupportedOperationException.class, () -> policy.getVersionWeights().clear());
    }

    @Test
    @DisplayName("未配置和已配置空表语义不同")
    void emptyPoliciesRemainExplicit() {
        assertTrue(new LingVersionPolicy(scope, "r", true, Collections.emptyMap()).isConfigured());
        assertFalse(new LingVersionPolicy(scope, "r", false, Collections.emptyMap()).isConfigured());
        assertThrows(IllegalArgumentException.class,
                () -> new LingVersionPolicy(scope, "r", false, Collections.singletonMap("v1", 1)));
    }

    @Test
    @DisplayName("拒绝非法作用域修订版本和权重")
    void rejectsInvalidPolicy() {
        assertThrows(NullPointerException.class, () -> new LingVersionPolicy(null, "r", true, Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class, () -> new LingVersionPolicy(scope, "", true, Collections.emptyMap()));
        assertThrows(NullPointerException.class, () -> new LingVersionPolicy(scope, "r", true, null));
        for (Integer weight : new Integer[] {null, -1, 101}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new LingVersionPolicy(scope, "r", true, Collections.singletonMap("v1", weight)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new LingVersionPolicy(scope, "r", true, Collections.singletonMap(" ", 1)));
    }
}
