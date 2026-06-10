package com.lingframe.api.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LingInvocationException 及 ErrorKind 枚举测试
 */
class LingInvocationExceptionTest {

    @Nested
    @DisplayName("ErrorKind 枚举")
    class ErrorKindTest {

        @Test
        @DisplayName("每个 ErrorKind 都有 LING-XXXX 格式错误码")
        void shouldHaveValidErrorCode() {
            for (LingInvocationException.ErrorKind kind : LingInvocationException.ErrorKind.values()) {
                assertTrue(kind.getCode().startsWith("LING-"),
                        kind.name() + " 的错误码应以 LING- 开头: " + kind.getCode());
            }
        }

        @Test
        @DisplayName("错误码唯一不重复")
        void shouldHaveUniqueErrorCodes() {
            long distinctCount = java.util.Arrays.stream(LingInvocationException.ErrorKind.values())
                    .map(LingInvocationException.ErrorKind::getCode)
                    .distinct()
                    .count();
            assertEquals(LingInvocationException.ErrorKind.values().length, distinctCount,
                    "所有 ErrorKind 的错误码应唯一");
        }

        @Test
        @DisplayName("valueOf 按名称查找")
        void shouldResolveByName() {
            assertEquals(LingInvocationException.ErrorKind.CIRCUIT_OPEN,
                    LingInvocationException.ErrorKind.valueOf("CIRCUIT_OPEN"));
            assertEquals(LingInvocationException.ErrorKind.TIMEOUT,
                    LingInvocationException.ErrorKind.valueOf("TIMEOUT"));
        }
    }

    @Nested
    @DisplayName("构造函数")
    class ConstructorTest {

        @Test
        @DisplayName("fqsid + kind 构造：自动生成 message")
        void shouldConstructWithFqsidAndKind() {
            LingInvocationException ex = new LingInvocationException(
                    "order-service.create", LingInvocationException.ErrorKind.ROUTE_FAILURE);

            assertEquals("order-service.create", ex.getFqsid());
            assertEquals(LingInvocationException.ErrorKind.ROUTE_FAILURE, ex.getKind());
            assertTrue(ex.getMessage().contains("LING-1001"));
            assertTrue(ex.getMessage().contains("order-service.create"));
        }

        @Test
        @DisplayName("fqsid + kind + message 构造：使用自定义 message")
        void shouldConstructWithCustomMessage() {
            LingInvocationException ex = new LingInvocationException(
                    "pay-service.charge", LingInvocationException.ErrorKind.SECURITY_REJECTED,
                    "权限不足");

            assertEquals("pay-service.charge", ex.getFqsid());
            assertEquals(LingInvocationException.ErrorKind.SECURITY_REJECTED, ex.getKind());
            assertEquals("权限不足", ex.getMessage());
        }

        @Test
        @DisplayName("fqsid + kind + cause 构造：保留原始异常")
        void shouldConstructWithCause() {
            RuntimeException cause = new RuntimeException("连接超时");
            LingInvocationException ex = new LingInvocationException(
                    "user-service.query", LingInvocationException.ErrorKind.TIMEOUT, cause);

            assertEquals("user-service.query", ex.getFqsid());
            assertEquals(LingInvocationException.ErrorKind.TIMEOUT, ex.getKind());
            assertSame(cause, ex.getCause());
        }
    }

    @Nested
    @DisplayName("异常继承链")
    class HierarchyTest {

        @Test
        @DisplayName("LingInvocationException 继承 LingRuntimeException")
        void shouldBeRuntimeException() {
            LingInvocationException ex = new LingInvocationException(
                    "test", LingInvocationException.ErrorKind.INTERNAL_ERROR);
            assertInstanceOf(LingRuntimeException.class, ex);
            assertInstanceOf(LingException.class, ex);
            assertInstanceOf(RuntimeException.class, ex);
        }

        @Test
        @DisplayName("LingRuntimeException 保留 lingId")
        void shouldPreserveLingId() {
            LingRuntimeException ex = new LingRuntimeException("my-ling", "发生错误");
            assertEquals("my-ling", ex.getLingId());
            assertEquals("发生错误", ex.getMessage());
        }

        @Test
        @DisplayName("LingRuntimeException 保留 cause")
        void shouldPreserveCause() {
            Throwable cause = new IllegalStateException("底层异常");
            LingRuntimeException ex = new LingRuntimeException("my-ling", "发生错误", cause);
            assertSame(cause, ex.getCause());
        }
    }
}
