package com.lingframe.core.fsm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("DefaultRuntimeEvaluationPolicy 测试")
class DefaultRuntimeEvaluationPolicyTest {

    private final DefaultRuntimeEvaluationPolicy policy = new DefaultRuntimeEvaluationPolicy();

    @Test
    @DisplayName("存在恢复中实例时应优先评估为 RECOVERING")
    void evaluate_ShouldPreferRecoveringWhenInstanceIsRecovering() {
        RuntimeStatus result = policy.evaluate(RuntimeStatus.DEGRADED,
                Arrays.asList(InstanceStatus.READY, InstanceStatus.RECOVERING));

        assertEquals(RuntimeStatus.RECOVERING, result);
    }

    @Test
    @DisplayName("恢复结束后应根据 READY 实例回到 ACTIVE")
    void evaluate_ShouldReturnActiveAfterRecoveringInstancesDisappear() {
        RuntimeStatus result = policy.evaluate(RuntimeStatus.RECOVERING,
                Arrays.asList(InstanceStatus.READY));

        assertEquals(RuntimeStatus.ACTIVE, result);
    }
}
