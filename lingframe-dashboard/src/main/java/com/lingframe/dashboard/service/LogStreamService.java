package com.lingframe.dashboard.service;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.dashboard.dto.LogStreamDTO;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
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
@RequiredArgsConstructor
public class LogStreamService {

    private final EventBus eventBus;
    /**
     * 维护所有活跃连接
     */
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * 单线程分发器，避免抢占业务线程池
     */
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ling-sse-dispatcher");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, ex) ->
                log.error("SSE dispatcher thread error", ex));
        return t;
    });

    /**
     * 心跳调度器，15秒发送一次心跳
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ling-sse-heartbeat");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, ex) ->
                log.error("SSE heartbeat thread error", ex));
        return t;
    });

    @PostConstruct
    public void init() {
        // 订阅内核事件
        eventBus.subscribe("lingframe-dashboard", MonitoringEvents.TraceLogEvent.class, this::handleTrace);
        eventBus.subscribe("lingframe-dashboard", MonitoringEvents.AuditLogEvent.class, this::handleAudit);

        // 启动心跳 (每15秒)，防止 Nginx/Browser 超时
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 15, 15, TimeUnit.SECONDS);
        log.info("[LingFrame Dashboard] LogStreamService initialized");
    }

    /**
     * 创建新的 SSE 连接
     */
    public SseEmitter createEmitter() {
        SseEmitter emitter = new SseEmitter(0L);

        emitter.onCompletion(() -> removeEmitter(emitter));
        emitter.onTimeout(() -> removeEmitter(emitter));
        emitter.onError((e) -> removeEmitter(emitter));

        emitters.add(emitter);
        log.info("New SSE connection. Active: {}", emitters.size());
        return emitter;
    }

    /**
     * 处理内核 Trace 日志事件
     */
    private void handleTrace(MonitoringEvents.TraceLogEvent event) {
        LogStreamDTO logStreamDTO = LogStreamDTO.builder()
                .type("TRACE")
                .traceId(event.getTraceId())
                .pluginId(event.getPluginId())
                .content(event.getAction())
                .tag(event.getType())
                .depth(event.getDepth())
                .timestamp(event.getTimestamp())
                .build();
        broadcast(logStreamDTO);
    }

    /**
     * 处理内核 Audit 日志事件
     */
    private void handleAudit(MonitoringEvents.AuditLogEvent event) {
        String content = String.format("%s on %s - %s (%.3fms)",
                event.getAction(), event.getResource(),
                event.isSuccess() ? "SUCCESS" : "DENIED", event.getCost() / 1_000_000.0);

        LogStreamDTO logStreamDTO = LogStreamDTO.builder()
                .type("AUDIT")
                .traceId(event.getTraceId())
                .pluginId(event.getPluginId())
                .content(content)
                .tag(event.isSuccess() ? "OK" : "FAIL")
                .timestamp(event.getTimestamp())
                .build();
        broadcast(logStreamDTO);
    }

    /**
     * 广播日志事件给所有连接
     */
    public void broadcast(LogStreamDTO logStreamDTO) {
        if (emitters.isEmpty()) return;

        // 异步提交给分发线程，不阻塞当前业务线程 (Core Kernel)
        dispatcher.submit(() -> {
            List<SseEmitter> dead = new ArrayList<>();
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("log-event")
                            .data(logStreamDTO, MediaType.APPLICATION_JSON));
                } catch (Exception e) {
                    dead.add(emitter);
                }
            }
            emitters.removeAll(dead);
        });
    }

    /**
     * 发送心跳事件给所有连接
     */
    private void sendHeartbeat() {
        if (emitters.isEmpty()) return;
        dispatcher.submit(() -> {
            List<SseEmitter> dead = new ArrayList<>();
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name("ping").data("pong"));
                } catch (Exception e) {
                    dead.add(emitter);
                }
            }
            emitters.removeAll(dead);
        });
    }

    /**
     * 移除已关闭的连接
     */
    private void removeEmitter(SseEmitter emitter) {
        emitters.remove(emitter);
        log.debug("SSE connection closed. Active: {}", emitters.size());
    }

    /**
     * 服务销毁时，清理所有连接
     */
    @PreDestroy
    public void cleanup() {
        dispatcher.shutdownNow();
        scheduler.shutdownNow();
        emitters.forEach(SseEmitter::complete);
    }
}