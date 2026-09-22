package com.lingframe.api.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 跨灵元事务穿透回滚信号异常测试：构造器语义与对齐 Spring UnexpectedRollbackException 的契约。
 */
@DisplayName("LingTransactionRollbackException 回滚信号异常")
class LingTransactionRollbackExceptionTest {

    @Test
    @DisplayName("message 构造器保留消息并继承 RuntimeException")
    void messageConstructorKeepsMessageAndIsRuntimeException() {
        LingTransactionRollbackException ex = new LingTransactionRollbackException("rollback signaled");

        assertEquals("rollback signaled", ex.getMessage());
        // 运行时异常：穿透过滤器回传路径直接抛出，不要求调用方显式捕获
        assertSame(RuntimeException.class, ex.getClass().getSuperclass());
    }

    @Test
    @DisplayName("message + cause 构造器保留消息与根因")
    void messageAndCauseConstructorKeepsBoth() {
        Throwable root = new IllegalStateException("downstream rollback");
        LingTransactionRollbackException ex = new LingTransactionRollbackException("rollback signaled", root);

        assertEquals("rollback signaled", ex.getMessage());
        assertSame(root, ex.getCause());
    }

    @Test
    @DisplayName("cause 构造器不吞掉空消息场景，message 可为空")
    void messageIsOptional() {
        LingTransactionRollbackException ex = new LingTransactionRollbackException(null);

        assertEquals(null, ex.getMessage());
    }

    @Test
    @DisplayName("异常可被 throwable 链正常传播（可捕获可被上层根事务感知）")
    void exceptionPropagatesThroughThrowableChain() {
        LingTransactionRollbackException ex = new LingTransactionRollbackException("propagate");

        // 模拟上层 catch 语义：对齐 Spring UnexpectedRollbackException 的捕获路径
        assertThrows(LingTransactionRollbackException.class, () -> {
            throw ex;
        });
    }
}
