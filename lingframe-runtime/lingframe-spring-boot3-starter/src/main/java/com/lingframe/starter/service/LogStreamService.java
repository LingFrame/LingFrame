package com.lingframe.starter.service;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.starter.model.LogStreamVO;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;

/**
 * SSE 日志流服务
 * 特性：
 * 1. 异步非阻塞分发
 * 2. 自动心跳保活
 * 3. 优雅关闭
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogStreamService {

    private final EventBus eventBus;

    // 维护所有活跃连接
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    // 单线程分发器，避免抢占业务线程池
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ling-sse-dispatcher");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, ex) ->
                log.error("SSE dispatcher thread error", ex));
        return t;
    });

    // 心跳调度器
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @PostConstruct
    public void init() {
        // 订阅内核事件
        eventBus.subscribe("lingframe", MonitoringEvents.TraceLogEvent.class, this::handleTrace);
        eventBus.subscribe("lingframe", MonitoringEvents.AuditLogEvent.class, this::handleAudit);

        // 启动心跳 (每15秒)，防止 Nginx/Browser 超时
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 15, 15, TimeUnit.SECONDS);
    }

    public SseEmitter createEmitter() {
        // timeout = 0 表示不过期 (由心跳维持)
        SseEmitter emitter = new SseEmitter(0L);

        emitter.onCompletion(() -> removeEmitter(emitter));
        emitter.onTimeout(() -> removeEmitter(emitter));
        emitter.onError((e) -> removeEmitter(emitter));

        emitters.add(emitter);
        log.debug("New SSE connection. Current active: {}", emitters.size());
        return emitter;
    }

    private void handleTrace(MonitoringEvents.TraceLogEvent event) {
        LogStreamVO vo = LogStreamVO.builder()
                .type("TRACE")
                .traceId(event.getTraceId())
                .pluginId(event.getPluginId())
                .content(event.getAction())
                .tag(event.getType()) // IN/OUT/ERROR
                .depth(event.getDepth())
                .timestamp(event.getTimestamp())
                .build();
        broadcast(vo);
    }

    private void handleAudit(MonitoringEvents.AuditLogEvent event) {
        String content = String.format("%s on %s - %s (%dms)",
                event.getAction(), event.getResource(),
                event.isSuccess() ? "SUCCESS" : "DENIED", event.getCost() / 1000);

        LogStreamVO vo = LogStreamVO.builder()
                .type("AUDIT")
                .traceId(event.getTraceId())
                .pluginId(event.getPluginId())
                .content(content)
                .tag(event.isSuccess() ? "OK" : "FAIL")
                .timestamp(event.getTimestamp())
                .build();
        broadcast(vo);
    }

    private void broadcast(LogStreamVO vo) {
        if (emitters.isEmpty()) return;

        // 异步提交给分发线程，不阻塞当前业务线程 (Core Kernel)
        dispatcher.submit(() -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name("log-event").data(vo));
                } catch (IOException | IllegalStateException e) {
                    removeEmitter(emitter);
                }
            }
        });
    }

    private void sendHeartbeat() {
        if (emitters.isEmpty()) return;
        dispatcher.submit(() -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name("ping").data("pong"));
                } catch (Exception e) {
                    removeEmitter(emitter);
                }
            }
        });
    }

    private void removeEmitter(SseEmitter emitter) {
        emitters.remove(emitter);
    }

    @PreDestroy
    public void cleanup() {
        dispatcher.shutdownNow();
        scheduler.shutdownNow();
        emitters.forEach(SseEmitter::complete);
    }
}