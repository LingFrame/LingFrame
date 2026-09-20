package com.lingframe.core.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 权重快照的值对象契约测试。 */
@DisplayName("不可变 provider 权重快照")
class ProviderWeightSnapshotTest {

    @Test
    @DisplayName("输入和输出均不能修改已创建的快照")
    void immutableCopy() {
        Map<String, Integer> input = new HashMap<>();
        input.put("a:v1", 100);
        ProviderWeightSnapshot snapshot = new ProviderWeightSnapshot("svc", "revision", input);
        input.put("a:v1", 0);
        assertEquals("svc", snapshot.getContractId());
        assertEquals("revision", snapshot.getRevision());
        assertEquals(100, snapshot.getWeights().get("a:v1"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getWeights().clear());
    }

    @Test
    @DisplayName("空表和边界权重有效且不要求权重之和为一百")
    void acceptsBoundaries() {
        assertTrue(new ProviderWeightSnapshot("svc", "r", Collections.emptyMap()).getWeights().isEmpty());
        Map<String, Integer> weights = new HashMap<>();
        weights.put("a", 100);
        weights.put("b", 100);
        weights.put("c", 0);
        assertEquals(weights, new ProviderWeightSnapshot("svc", "r", weights).getWeights());
    }

    @Test
    @DisplayName("非法标识和权重应在创建时拒绝")
    void rejectsInvalidInput() {
        for (String key : new String[] {null, "", " "}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ProviderWeightSnapshot(key, "r", Collections.emptyMap()));
            assertThrows(IllegalArgumentException.class,
                    () -> new ProviderWeightSnapshot("svc", key, Collections.emptyMap()));
            assertThrows(IllegalArgumentException.class,
                    () -> new ProviderWeightSnapshot("svc", "r", Collections.singletonMap(key, 1)));
        }
        for (Integer weight : new Integer[] {null, -1, 101}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ProviderWeightSnapshot("svc", "r", Collections.singletonMap("a", weight)));
        }
        assertThrows(NullPointerException.class, () -> new ProviderWeightSnapshot("svc", "r", null));
    }
}
