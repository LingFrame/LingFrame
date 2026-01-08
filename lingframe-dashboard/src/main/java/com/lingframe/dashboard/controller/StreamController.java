package com.lingframe.dashboard.controller;

import com.lingframe.dashboard.service.LogStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/lingframe/dashboard")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@ConditionalOnProperty(
        prefix = "lingframe.dashboard",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class StreamController {

    private final LogStreamService logStreamService;

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs() {
        return logStreamService.createEmitter();
    }
}