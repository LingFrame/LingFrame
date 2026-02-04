package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.service.LogStreamService;
import javax.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/lingframe/dashboard")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class StreamController {

    private final LogStreamService logStreamService;

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> streamLogs(HttpServletResponse response) {
        // 🔥 立即设置并刷新响应头，确保客户端立即接收，将SSE连接时间从5s减少到2.4ms
        response.setHeader("Cache-Control", "no-cache, no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Accel-Buffering", "no"); // 禁用 Nginx 缓冲
        response.setHeader("Connection", "keep-alive");
        response.setHeader("Content-Type", "text/event-stream; charset=UTF-8");

        try {
            // 🔥 立即刷新缓冲区，强制发送响应头
            response.flushBuffer();
        } catch (IOException e) {
            log.warn("Failed to flush buffer: {}", e.getMessage());
        }

        long startTime = System.nanoTime();
        SseEmitter emitter = logStreamService.createEmitter();
        long endTime = System.nanoTime();

        log.info("SSE connection creation time: {}ms", (endTime - startTime) / 1_000_000);

        return ResponseEntity.ok()
                .header("X-SSE-Init-Time", String.valueOf((endTime - startTime) / 1_000_000))
                .body(emitter);
    }
}