package com.lingframe.dashboard.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ApiResponse 错误响应脱敏测试")
class ApiResponseTest {

    @Nested
    @DisplayName("error(String) 脱敏")
    class ErrorMessageSanitization {

        @Test
        @DisplayName("普通业务消息应原样保留")
        void shouldKeepPlainMessage() {
            ApiResponse<Void> resp = ApiResponse.error("providerKey 不能为空");
            assertFalse(resp.isSuccess());
            assertEquals("providerKey 不能为空", resp.getMessage());
        }

        @Test
        @DisplayName("含堆栈帧行的消息应被剥离")
        void shouldStripStackTraceFrame() {
            ApiResponse<Void> resp = ApiResponse.error(
                    "加载失败: 配置错误 at com.lingframe.Example.load(Example.java:42)");
            assertFalse(resp.isSuccess());
            assertEquals("加载失败: 配置错误", resp.getMessage());
        }

        @Test
        @DisplayName("含内部类路径的消息应被剥离")
        void shouldStripInternalClassPath() {
            ApiResponse<Void> resp = ApiResponse.error("加载失败: java.lang.ClassNotFoundException");
            assertFalse(resp.isSuccess());
            assertEquals("加载失败:", resp.getMessage());
        }

        @Test
        @DisplayName("null 消息应返回 null")
        void shouldKeepNullMessage() {
            ApiResponse<Void> resp = ApiResponse.error((String) null);
            assertFalse(resp.isSuccess());
            assertNull(resp.getMessage());
        }
    }

    @Nested
    @DisplayName("error(Throwable) 脱敏")
    class ErrorThrowableSanitization {

        @Test
        @DisplayName("IllegalArgumentException 应返回其消息")
        void shouldReturnMessageForIllegalArgument() {
            ApiResponse<Void> resp = ApiResponse.error(new IllegalArgumentException("参数错误"));
            assertFalse(resp.isSuccess());
            assertEquals("参数错误", resp.getMessage());
        }

        @Test
        @DisplayName("IllegalStateException 应返回其消息")
        void shouldReturnMessageForIllegalState() {
            ApiResponse<Void> resp = ApiResponse.error(new IllegalStateException("状态冲突"));
            assertFalse(resp.isSuccess());
            assertEquals("状态冲突", resp.getMessage());
        }

        @Test
        @DisplayName("通用异常应返回通用提示，不泄漏内部细节")
        void shouldReturnGenericMessageForUnknown() {
            ApiResponse<Void> resp = ApiResponse.error(new RuntimeException("sensitive internal detail"));
            assertFalse(resp.isSuccess());
            assertEquals("服务内部错误，请稍后重试", resp.getMessage());
        }
    }

    @Nested
    @DisplayName("ok 响应")
    class OkResponse {

        @Test
        @DisplayName("ok 应返回 success=true")
        void shouldReturnSuccess() {
            ApiResponse<String> resp = ApiResponse.ok("ok");
            assertTrue(resp.isSuccess());
            assertEquals("ok", resp.getData());
        }
    }
}
