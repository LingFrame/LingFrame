package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.security.SseTicketController;
import com.lingframe.dashboard.service.LogStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SSE 日志流控制器测试
 * 覆盖 ticket 校验的两条路径：无效→auth-error emitter，有效→logStreamService emitter
 */
@DisplayName("日志流控制器测试")
class StreamControllerTest {

    private LogStreamService logStreamService;
    private SseTicketController sseTicketController;
    private StreamController controller;

    @BeforeEach
    void setUp() {
        logStreamService = mock(LogStreamService.class);
        sseTicketController = mock(SseTicketController.class);
        controller = new StreamController(logStreamService, sseTicketController);
    }

    @Nested
    @DisplayName("streamLogs")
    class StreamLogsTests {

        @Test
        @DisplayName("ticket 无效时应返回 auth-error SseEmitter，不调用 logStreamService")
        void shouldReturnAuthErrorWhenTicketInvalid() {
            when(sseTicketController.validateAndConsume("bad-ticket")).thenReturn(false);

            ResponseEntity<SseEmitter> response = controller.streamLogs("bad-ticket");

            assertEquals(200, response.getStatusCodeValue());
            assertNotNull(response.getBody());
            verify(logStreamService, never()).createEmitter();
        }

        @Test
        @DisplayName("ticket 为 null 时应返回 auth-error")
        void shouldReturnAuthErrorWhenTicketNull() {
            when(sseTicketController.validateAndConsume(null)).thenReturn(false);

            ResponseEntity<SseEmitter> response = controller.streamLogs(null);

            assertEquals(200, response.getStatusCodeValue());
            assertNotNull(response.getBody());
            verify(logStreamService, never()).createEmitter();
        }

        @Test
        @DisplayName("ticket 有效时应返回 logStreamService 的 SseEmitter")
        void shouldReturnEmitterWhenTicketValid() {
            SseEmitter emitter = new SseEmitter(0L);
            when(sseTicketController.validateAndConsume("good-ticket")).thenReturn(true);
            when(logStreamService.createEmitter()).thenReturn(emitter);

            ResponseEntity<SseEmitter> response = controller.streamLogs("good-ticket");

            assertEquals(200, response.getStatusCodeValue());
            assertSame(emitter, response.getBody());
            verify(logStreamService).createEmitter();
        }

        @Test
        @DisplayName("无效 ticket 与有效 ticket 应返回不同的 emitter 实例")
        void shouldReturnDifferentEmittersForDifferentTickets() {
            SseEmitter validEmitter = new SseEmitter(0L);
            when(sseTicketController.validateAndConsume("bad")).thenReturn(false);
            when(sseTicketController.validateAndConsume("good")).thenReturn(true);
            when(logStreamService.createEmitter()).thenReturn(validEmitter);

            ResponseEntity<SseEmitter> invalidResponse = controller.streamLogs("bad");
            ResponseEntity<SseEmitter> validResponse = controller.streamLogs("good");

            assertNotSame(invalidResponse.getBody(), validResponse.getBody());
            assertSame(validEmitter, validResponse.getBody());
        }
    }
}
