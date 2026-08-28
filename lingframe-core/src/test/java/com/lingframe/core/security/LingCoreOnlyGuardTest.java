package com.lingframe.core.security;

import com.lingframe.api.context.LingCallContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证灵核专属操作守卫（阶段 1a 铁线、双保险之第二道）。
 *
 * <p>核心语义：当前线程若处于灵元执行栈（LingCallContext 已标记 lingId）→ 拒绝影响性操作；
 * 灵核上下文（无标记）→ 放行。代理灵元即便绕过入口、直持底层 Bean/registry 引用，只要在
 * 自身执行栈内调用影响性操作底层方法，也会命中此守卫。
 */
class LingCoreOnlyGuardTest {

    @AfterEach
    void tearDown() {
        LingCallContext.clear();
    }

    @Test
    @DisplayName("灵核上下文（无 lingId）放行影响性操作")
    void allowsLingCoreContext() {
        assertDoesNotThrow(() -> LingCoreOnlyGuard.assertLingCoreContext("unload ling"));
    }

    @Test
    @DisplayName("处于灵元执行栈（有 lingId）时拒绝影响性操作")
    void rejectsInAgentContext() {
        LingCallContext.setLingId("malicious-agent");

        SecurityException ex = assertThrows(SecurityException.class,
                () -> LingCoreOnlyGuard.assertLingCoreContext("unload ling"));

        assertTrue(ex.getMessage().contains("in-ling context [malicious-agent]"),
                "拒绝信息应含触发越权的灵元身份");
    }

    @Test
    @DisplayName("拒绝与具体操作名无关,任何影响性操作一律拦截")
    void rejectsAnyOperationInAgentContext() {
        LingCallContext.setLingId("biz-agent");

        assertThrows(SecurityException.class, () -> LingCoreOnlyGuard.assertLingCoreContext("update ling"));
        assertThrows(SecurityException.class, () -> LingCoreOnlyGuard.assertLingCoreContext("promote canary"));
        assertThrows(SecurityException.class, () -> LingCoreOnlyGuard.assertLingCoreContext("rollback canary"));
        assertThrows(SecurityException.class, () -> LingCoreOnlyGuard.assertLingCoreContext("update canary config"));
    }
}
