package com.lingframe.api.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异常层级契约测试。
 * <p>
 * 验证 LingException → LingRuntimeException → LingInvocationException 继承链的
 * 错误码、消息格式和异常链传播契约。
 */
@DisplayName("异常层级契约测试")
class ExceptionHierarchyContractTest {

    @Nested
    @DisplayName("LingException 契约")
    class LingExceptionContract {

        @Test
        @DisplayName("消息正确传递")
        void messagePreserved() {
            LingException ex = new LingException("test-message");
            assertEquals("test-message", ex.getMessage());
        }

        @Test
        @DisplayName("异常链正确传播")
        void causePreserved() {
            RuntimeException cause = new RuntimeException("root-cause");
            LingException ex = new LingException("wrapper", cause);
            assertEquals("wrapper", ex.getMessage());
            assertEquals("root-cause", ex.getCause().getMessage());
        }
    }

    @Nested
    @DisplayName("LingRuntimeException 契约")
    class LingRuntimeExceptionContract {

        @Test
        @DisplayName("lingId 和消息正确传递")
        void lingIdAndMessagePreserved() {
            LingRuntimeException ex = new LingRuntimeException("ling-1", "error");
            assertEquals("ling-1", ex.getLingId());
            assertEquals("error", ex.getMessage());
        }

        @Test
        @DisplayName("继承 LingException")
        void extendsLingException() {
            assertTrue(LingException.class.isAssignableFrom(LingRuntimeException.class));
        }
    }

    @Nested
    @DisplayName("LingInvocationException 契约")
    class LingInvocationExceptionContract {

        @Test
        @DisplayName("fqsid 和 ErrorKind 正确传递")
        void fqsidAndKindPreserved() {
            LingInvocationException ex = new LingInvocationException(
                    "ling-1:send_sms", LingInvocationException.ErrorKind.CIRCUIT_OPEN);
            assertEquals("ling-1:send_sms", ex.getFqsid());
            assertEquals(LingInvocationException.ErrorKind.CIRCUIT_OPEN, ex.getKind());
        }

        @Test
        @DisplayName("默认消息包含 ErrorKind 和 fqsid")
        void defaultMessageContainsKindAndFqsid() {
            LingInvocationException ex = new LingInvocationException(
                    "ling-1:send_sms", LingInvocationException.ErrorKind.RATE_LIMITED);
            String msg = ex.getMessage();
            assertTrue(msg.contains("RATE_LIMITED"), "消息应包含 ErrorKind 名称");
            assertTrue(msg.contains("ling-1:send_sms"), "消息应包含 fqsid");
        }

        @Test
        @DisplayName("自定义消息覆盖默认消息")
        void customMessageOverridesDefault() {
            LingInvocationException ex = new LingInvocationException(
                    "ling-1:svc", LingInvocationException.ErrorKind.TIMEOUT, "custom-msg");
            assertEquals("custom-msg", ex.getMessage());
        }

        @Test
        @DisplayName("异常链正确传播")
        void causePreserved() {
            RuntimeException cause = new RuntimeException("timeout");
            LingInvocationException ex = new LingInvocationException(
                    "ling-1:svc", LingInvocationException.ErrorKind.TIMEOUT, cause);
            assertEquals("timeout", ex.getCause().getMessage());
            assertEquals("ling-1:svc", ex.getFqsid());
        }

        @Test
        @DisplayName("继承 LingRuntimeException")
        void extendsLingRuntimeException() {
            assertTrue(LingRuntimeException.class.isAssignableFrom(LingInvocationException.class));
        }
    }

    @Nested
    @DisplayName("ErrorKind 错误码契约")
    class ErrorKindContract {

        @Test
        @DisplayName("所有 ErrorKind 都有 LING-XXXX 格式错误码")
        void allErrorKindsHaveCodeFormat() {
            for (LingInvocationException.ErrorKind kind : LingInvocationException.ErrorKind.values()) {
                assertTrue(kind.getCode().matches("LING-\\d{4}"),
                        kind.name() + " 的错误码格式应为 LING-XXXX，实际: " + kind.getCode());
            }
        }

        @Test
        @DisplayName("错误码全局唯一")
        void errorCodesAreUnique() {
            long distinctCount = Arrays.stream(LingInvocationException.ErrorKind.values())
                    .map(LingInvocationException.ErrorKind::getCode)
                    .distinct()
                    .count();
            assertEquals(LingInvocationException.ErrorKind.values().length, distinctCount,
                    "所有 ErrorKind 的错误码应唯一");
        }
    }

    @Nested
    @DisplayName("异常层级继承契约")
    class HierarchyContract {

        @Test
        @DisplayName("catch(LingException) 能统一捕获所有框架异常")
        void lingExceptionCatchesAll() {
            LingException[] exceptions = {
                    new LingException("base"),
                    new LingRuntimeException("ling-1", "runtime"),
                    new LingInvocationException("ling-1:svc", LingInvocationException.ErrorKind.INVOKE_ERROR),
                    new ServiceNotFoundException("svc-1"),
                    new PermissionDeniedException("perm-1"),
                    new LingNotFoundException("ling-1")
            };
            for (LingException ex : exceptions) {
                assertNotNull(ex.getMessage(), ex.getClass().getSimpleName() + " 的消息不应为 null");
            }
        }
    }
}
