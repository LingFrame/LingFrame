package com.lingframe.core.fsm;

import com.lingframe.core.fsm.RuntimeStatus.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RuntimeStatus 枚举与状态机初始化测试。
 */
@DisplayName("RuntimeStatus 测试")
class RuntimeStatusTest {

    @Test
    @DisplayName("所有枚举值都必须明确绑定 Kind 且不为 null")
    void allStatusesMustHaveNonNullKind() {
        for (RuntimeStatus status : RuntimeStatus.values()) {
            assertNotNull(status.kind(), "RuntimeStatus " + status + " must have a non-null Kind");
        }
    }

    @ParameterizedTest
    @EnumSource(value = RuntimeStatus.class, names = {"INACTIVE", "ACTIVE", "DEGRADED"})
    @DisplayName("事实态枚举的 Kind 应为 FACT 且不压制评估")
    void factStatusesShouldBeFactAndNotSuppressEvaluation(RuntimeStatus status) {
        assertEquals(Kind.FACT, status.kind());
        assertFalse(status.suppressesEvaluation());
    }

    @ParameterizedTest
    @EnumSource(value = RuntimeStatus.class, names = {"RECOVERING", "STOPPING"})
    @DisplayName("意图态枚举的 Kind 应为 INTENT 且压制评估")
    void intentStatusesShouldBeIntentAndSuppressEvaluation(RuntimeStatus status) {
        assertEquals(Kind.INTENT, status.kind());
        assertTrue(status.suppressesEvaluation());
    }

    @Test
    @DisplayName("终态 REMOVED 的 Kind 应为 TERMINAL 且压制评估")
    void removedStatusShouldBeTerminalAndSuppressEvaluation() {
        assertEquals(Kind.TERMINAL, RuntimeStatus.REMOVED.kind());
        assertTrue(RuntimeStatus.REMOVED.suppressesEvaluation());
    }

    @Test
    @DisplayName("newMachine 创建的状态机初始状态应为 INACTIVE")
    void newMachineInitialStateShouldBeInactive() {
        StateMachine<RuntimeStatus> fsm = RuntimeStatus.newMachine("test-ling");
        assertNotNull(fsm);
        assertEquals(RuntimeStatus.INACTIVE, fsm.current());
        assertEquals("test-ling", fsm.contextId());
    }

    @Test
    @DisplayName("合法转换表不为 null 且不可修改")
    void transitionsShouldBeImmutable() {
        assertNotNull(RuntimeStatus.TRANSITIONS);
        assertFalse(RuntimeStatus.TRANSITIONS.isEmpty());
    }
}
