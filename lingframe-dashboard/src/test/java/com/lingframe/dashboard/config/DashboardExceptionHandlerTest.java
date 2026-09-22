package com.lingframe.dashboard.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 仪表盘全局异常处理器测试
 * 覆盖 3 个异常映射（400/409/500）、null message 默认值、@ResponseStatus 注解契约
 */
@DisplayName("仪表盘全局异常处理器测试")
class DashboardExceptionHandlerTest {

    private final DashboardExceptionHandler handler = new DashboardExceptionHandler();

    @Nested
    @DisplayName("handleBadRequest")
    class HandleBadRequestTests {

        @Test
        @DisplayName("IllegalArgumentException 应返回 success=false + 原始消息")
        void shouldReturnBadRequestWithMessage() {
            Map<String, Object> result = handler.handleBadRequest(
                    new IllegalArgumentException("invalid param"));

            assertEquals(false, result.get("success"));
            assertEquals("invalid param", result.get("message"));
        }

        @Test
        @DisplayName("null message 应返回默认提示 '参数错误'")
        void shouldReturnDefaultMessageWhenNull() {
            Map<String, Object> result = handler.handleBadRequest(
                    new IllegalArgumentException());

            assertEquals(false, result.get("success"));
            assertEquals("参数错误", result.get("message"));
        }
    }

    @Nested
    @DisplayName("handleConflict")
    class HandleConflictTests {

        @Test
        @DisplayName("IllegalStateException 应返回 success=false + 原始消息")
        void shouldReturnConflictWithMessage() {
            Map<String, Object> result = handler.handleConflict(
                    new IllegalStateException("state conflict"));

            assertEquals(false, result.get("success"));
            assertEquals("state conflict", result.get("message"));
        }

        @Test
        @DisplayName("null message 应返回默认提示 '操作冲突'")
        void shouldReturnDefaultMessageWhenNull() {
            Map<String, Object> result = handler.handleConflict(
                    new IllegalStateException());

            assertEquals(false, result.get("success"));
            assertEquals("操作冲突", result.get("message"));
        }
    }

    @Nested
    @DisplayName("handleGeneral")
    class HandleGeneralTests {

        @Test
        @DisplayName("通用异常应返回 '服务内部错误' 且不泄漏原始消息")
        void shouldReturnGenericMessageWithoutLeakingStack() {
            Map<String, Object> result = handler.handleGeneral(
                    new RuntimeException("sensitive internal detail"));

            assertEquals(false, result.get("success"));
            assertEquals("服务内部错误，请稍后重试", result.get("message"));
            // 确保不泄漏原始异常消息
            assertNotEquals("sensitive internal detail", result.get("message"));
        }
    }

    @Nested
    @DisplayName("注解契约测试")
    class AnnotationContractTests {

        @Test
        @DisplayName("handleBadRequest 应标注 @ResponseStatus(BAD_REQUEST)")
        void shouldHaveBadRequestStatus() throws NoSuchMethodException {
            ResponseStatus annotation = DashboardExceptionHandler.class
                    .getMethod("handleBadRequest", IllegalArgumentException.class)
                    .getAnnotation(ResponseStatus.class);

            assertNotNull(annotation);
            assertEquals(HttpStatus.BAD_REQUEST, annotation.value());
        }

        @Test
        @DisplayName("handleConflict 应标注 @ResponseStatus(CONFLICT)")
        void shouldHaveConflictStatus() throws NoSuchMethodException {
            ResponseStatus annotation = DashboardExceptionHandler.class
                    .getMethod("handleConflict", IllegalStateException.class)
                    .getAnnotation(ResponseStatus.class);

            assertNotNull(annotation);
            assertEquals(HttpStatus.CONFLICT, annotation.value());
        }

        @Test
        @DisplayName("handleGeneral 应标注 @ResponseStatus(INTERNAL_SERVER_ERROR)")
        void shouldHaveInternalServerErrorStatus() throws NoSuchMethodException {
            ResponseStatus annotation = DashboardExceptionHandler.class
                    .getMethod("handleGeneral", Exception.class)
                    .getAnnotation(ResponseStatus.class);

            assertNotNull(annotation);
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, annotation.value());
        }
    }
}
