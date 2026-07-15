package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.security.SseTicketController;
import com.lingframe.dashboard.service.LogStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 日志流 Controller
 * 直接返回 SseEmitter，避免 ResponseEntity 包装的 content negotiation 开销。
 * 通过 ticket 机制认证（EventSource 不支持自定义 Header）。
 */
@Slf4j
@RestController
@RequestMapping("/lingframe/dashboard")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class StreamController {

    private final LogStreamService logStreamService;
    private final SseTicketController sseTicketController;

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamLogs(@RequestParam(value = "ticket", required = false) String ticket) {
        // 如果配置了 token，则必须提供有效 ticket
        if (!sseTicketController.validateAndConsume(ticket)) {
            // 返回一个短期 SseEmitter 发送认证失败事件，而非 401
            // 原因：EventSource 遇到 401 会无限自动重试，无法被前端 onerror 区分处理
            return shortLivedEmitter("auth-error", "{\"message\":\"Unauthorized\"}");
        }
        try {
            return ResponseEntity.ok(logStreamService.createEmitter());
        } catch (IllegalStateException e) {
            // 连接数打满：返回短期 SseEmitter 发送 max-connections 事件，而非 500
            // 原因：直接抛 500 会让 EventSource 无限重连，放大重试风暴
            return shortLivedEmitter("max-connections", "{\"message\":\"Max SSE connections reached\"}");
        }
    }

    /**
     * 构造短期 SseEmitter 发送单条事件后立即完成。
     * <p>
     * 用于在认证失败、连接打满等异常场景下，向 EventSource 客户端推送
     * 可识别的事件而非返回 HTTP 错误码——避免 EventSource 自动无限重连。
     */
    private ResponseEntity<SseEmitter> shortLivedEmitter(String eventName, String data) {
        SseEmitter emitter = new SseEmitter(3000L);
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            emitter.complete();
        } catch (Exception ignored) {
            // emitter 已关闭
        }
        return ResponseEntity.ok(emitter);
    }
}
