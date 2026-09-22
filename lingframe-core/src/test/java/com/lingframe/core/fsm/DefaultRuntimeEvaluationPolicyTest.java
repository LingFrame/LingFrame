package com.lingframe.core.fsm;

import com.lingframe.core.fsm.RuntimeStatus.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("DefaultRuntimeEvaluationPolicy 测试")
class DefaultRuntimeEvaluationPolicyTest {

    private final DefaultRuntimeEvaluationPolicy policy = new DefaultRuntimeEvaluationPolicy();

    @Test
    @DisplayName("存在恢复中实例与就绪实例共存时应聚合为 ACTIVE（不越权输出 RECOVERING）")
    void evaluate_ShouldEvaluateToActiveWhenReadyAndRecoveringCoexist() {
        RuntimeStatus result = policy.evaluate(RuntimeStatus.DEGRADED,
                Arrays.asList(InstanceStatus.READY, InstanceStatus.RECOVERING));

        assertEquals(RuntimeStatus.ACTIVE, result);
        assertEquals(Kind.FACT, result.kind());
    }

    @Test
    @DisplayName("仅存在受控恢复中实例时应保持当前状态避免抖动")
    void evaluate_ShouldKeepCurrentWhenOnlyRecoveringInstancesExist() {
        RuntimeStatus result = policy.evaluate(RuntimeStatus.DEGRADED,
                Collections.singletonList(InstanceStatus.RECOVERING));

        assertEquals(RuntimeStatus.DEGRADED, result);
        assertEquals(Kind.FACT, result.kind());
    }

    @Test
    @DisplayName("无活跃实例时应返回 INACTIVE")
    void evaluate_ShouldReturnInactiveWhenNoInstances() {
        RuntimeStatus result = policy.evaluate(RuntimeStatus.ACTIVE, Collections.emptyList());

        assertEquals(RuntimeStatus.INACTIVE, result);
        assertEquals(Kind.FACT, result.kind());
    }

    @Test
    @DisplayName("错误实例占比超过阈值时应降级为 DEGRADED")
    void evaluate_ShouldDegradeWhenErrorRateExceedsThreshold() {
        RuntimeStatus result = policy.evaluate(RuntimeStatus.ACTIVE,
                Arrays.asList(InstanceStatus.READY, InstanceStatus.ERROR));

        assertEquals(RuntimeStatus.DEGRADED, result);
        assertEquals(Kind.FACT, result.kind());
    }

    @Test
    @DisplayName("恢复结束后应根据 READY 实例回到 ACTIVE")
    void evaluate_ShouldReturnActiveAfterRecoveringInstancesDisappear() {
        RuntimeStatus result = policy.evaluate(RuntimeStatus.DEGRADED,
                Collections.singletonList(InstanceStatus.READY));

        assertEquals(RuntimeStatus.ACTIVE, result);
        assertEquals(Kind.FACT, result.kind());
    }
}

