package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.service.LogStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 日志流 Controller
 * 直接返回 SseEmitter，避免 ResponseEntity 包装的 content negotiation 开销。
 */
@Slf4j
@RestController
@RequestMapping("/lingframe/dashboard")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class StreamController {

    private final LogStreamService logStreamService;

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs() {
        return logStreamService.createEmitter();
    }
}
