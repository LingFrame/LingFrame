package com.lingframe.dashboard.service;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.InstanceDestroyedEvent;
import com.lingframe.core.event.InstanceStateChangedEvent;
import com.lingframe.core.event.RuntimeStateChangedEvent;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.api.event.lifecycle.LingInstalledEvent;
import com.lingframe.api.event.lifecycle.LingInstallingEvent;
import com.lingframe.api.event.lifecycle.LingUninstalledEvent;
import com.lingframe.api.event.lifecycle.LingUninstallingEvent;
import com.lingframe.dashboard.dto.LogStreamDTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * SSE 日志流服务。
 *
 * <p>特性：
 * <ul>
 *   <li>异步非阻塞分发 - 使用独立线程池处理事件推送，不阻塞业务线程</li>
 *   <li>自动心跳保活 - 每15秒发送心跳，防止连接超时</li>
 *   <li>优雅关闭 - 服务销毁时清理所有连接和线程池</li>
 *   <li>ClassLoader 隔离 - 确保在正确的 ClassLoader 上下文中执行</li>
 * </ul>
 *
 * <p>订阅的事件类型：
 * <ul>
 *   <li>TraceLogEvent - 方法级全链路追踪日志</li>
 *   <li>AuditLogEvent - 权限审计日志</li>
 *   <li>AlertNotifyEvent - 告警通知</li>
 *   <li>CircuitBreakerStateEvent - 熔断器状态变化</li>
 *   <li>LeakDetectionEvent - GC 泄漏检测结果</li>
 *   <li>InstanceStateChangedEvent - 实例状态变化</li>
 *   <li>RuntimeStateChangedEvent - 运行时状态变化</li>
 *   <li>InstanceDestroyedEvent - 实例销毁</li>
 *   <li>LingInstallingEvent - 灵元安装中</li>
 *   <li>LingInstalledEvent - 灵元安装完成</li>
 *   <li>LingUninstallingEvent - 灵元卸载中</li>
 *   <li>LingUninstalledEvent - 灵元卸载完成</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class LogStreamService implements InitializingBean, DisposableBean {

    // 仪表盘自行维护格式化逻辑，避免新增事件字段反向污染核心事件模型。
    private final EventBus eventBus;
    private static final ClassLoader CORE_CLASSLOADER = LogStreamService.class.getClassLoader();

    /**
     * 维护所有活跃的 SSE 连接。
     */
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * 单线程分发器，避免抢占业务线程池
     */
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ling-sse-dispatcher");
        t.setDaemon(true);
        t.setContextClassLoader(CORE_CLASSLOADER);
        t.setUncaughtExceptionHandler((thread, ex) -> log.error("SSE dispatcher thread error", ex));
        return t;
    });

    /**
     * 心跳调度器，每 15 秒发送一次心跳
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ling-sse-heartbeat");
        t.setDaemon(true);
        t.setContextClassLoader(CORE_CLASSLOADER);
        t.setUncaughtExceptionHandler((thread, ex) -> log.error("SSE heartbeat thread error", ex));
        return t;
    });

    /**
     * 初始化：订阅内核事件并启动心跳
     */
    @Override
    public void afterPropertiesSet() {
        // 订阅内核监控事件
        eventBus.subscribe("lingframe-dashboard", MonitoringEvents.TraceLogEvent.class, this::handleTrace);
        eventBus.subscribe("lingframe-dashboard", MonitoringEvents.AuditLogEvent.class, this::handleAudit);
        eventBus.subscribe("lingframe-dashboard", MonitoringEvents.AlertNotifyEvent.class, this::handleAlert);
        eventBus.subscribe("lingframe-dashboard", MonitoringEvents.CircuitBreakerStateEvent.class, this::handleCircuitBreaker);
        eventBus.subscribe("lingframe-dashboard", MonitoringEvents.LeakDetectionEvent.class, this::handleLeakDetection);
        eventBus.subscribe("lingframe-dashboard", MonitoringEvents.ResourceCleanupCapabilityEvent.class, this::handleCleanupCapability);

        // 订阅状态变化事件
        eventBus.subscribe("lingframe-dashboard", InstanceStateChangedEvent.class, this::handleInstanceStateChange);
        eventBus.subscribe("lingframe-dashboard", RuntimeStateChangedEvent.class, this::handleRuntimeStateChange);
        eventBus.subscribe("lingframe-dashboard", InstanceDestroyedEvent.class, this::handleInstanceDestroyed);

        // 订阅生命周期事件
        eventBus.subscribe("lingframe-dashboard", LingInstallingEvent.class, this::handleLingInstalling);
        eventBus.subscribe("lingframe-dashboard", LingInstalledEvent.class, this::handleLingInstalled);
        eventBus.subscribe("lingframe-dashboard", LingUninstallingEvent.class, this::handleLingUninstalling);
        eventBus.subscribe("lingframe-dashboard", LingUninstalledEvent.class, this::handleLingUninstalled);

        // 启动心跳（每 15 秒），防止 Nginx / Browser 超时
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 15, 15, TimeUnit.SECONDS);
        log.info("[LingFrame Dashboard] LogStreamService initialized");
    }

    /**
     * 创建新的 SSE 连接。
     *
     * @return SSE 发射器实例
     */
    public SseEmitter createEmitter() {
        SseEmitter emitter = new SseEmitter(0L);

        emitter.onCompletion(() -> removeEmitter(emitter));
        emitter.onTimeout(() -> removeEmitter(emitter));
        emitter.onError((e) -> removeEmitter(emitter));

        emitters.add(emitter);

        // 立即发送初始事件，触发 response flush
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (Exception e) {
            log.warn("Failed to send initial SSE event", e);
        }

        log.info("New SSE connection. Active: {}", emitters.size());
        return emitter;
    }

    /**
     * 处理内核 Trace 日志事件（方法级全链路追踪）
     */
    private void handleTrace(MonitoringEvents.TraceLogEvent event) {
        LogStreamDTO logStreamDTO = LogStreamDTO.builder()
                .type("TRACE")
                .traceId(event.getTraceId())
                .lingId(event.getLingId())
                .content(event.getAction())
                .tag(event.getType())
                .depth(event.getDepth())
                .timestamp(event.getTimestamp())
                .build();
        broadcast(logStreamDTO);
    }

    /**
     * 处理内核 Audit 日志事件（权限审计）
     */
    private void handleAudit(MonitoringEvents.AuditLogEvent event) {
        StringBuilder content = new StringBuilder();
        content.append(event.getAction()).append(" on ").append(event.getResource())
                .append(" - ").append(event.getResult())
                .append(String.format(" (%.3fms)", event.getCostNanos() / 1_000_000.0));
        if (event.getCapability() != null && !event.getCapability().isEmpty()) {
            content.append(" [").append(event.getCapability()).append("]");
        }
        if (event.getPrincipal() != null && !event.getPrincipal().isEmpty()) {
            content.append(" principal=").append(event.getPrincipal());
        }
        if (event.getSource() != null && !event.getSource().isEmpty()) {
            content.append(" source=").append(event.getSource());
        }
        if (event.getRuleSource() != null && !event.getRuleSource().isEmpty()) {
            content.append(" ruleSource=").append(event.getRuleSource());
        }
        if (event.getFailureReason() != null && !event.getFailureReason().isEmpty()) {
            content.append(" reason=").append(event.getFailureReason());
        }

        LogStreamDTO logStreamDTO = LogStreamDTO.builder()
                .type("AUDIT")
                .traceId(event.getTraceId())
                .lingId(event.getLingId())
                .content(content.toString())
                .tag(event.getResult() == null ? "UNKNOWN" : event.getResult().name())
                .level(resolveAuditLevel(event))
                .timestamp(event.getTimestamp())
                .build();
        broadcast(logStreamDTO);
    }

    /**
     * 处理告警通知事件
     */
    private void handleAlert(MonitoringEvents.AlertNotifyEvent event) {
        StringBuilder content = new StringBuilder(event.getMessage());
        if (event.getSource() != null && !event.getSource().isEmpty()) {
            content.append(" source=").append(event.getSource());
        }
        if (event.getRuleSource() != null && !event.getRuleSource().isEmpty()) {
            content.append(" ruleSource=").append(event.getRuleSource());
        }

        LogStreamDTO logStreamDTO = LogStreamDTO.builder()
                .type("ALERT")
                .traceId(event.getTraceId())
                .lingId(event.getLingId())
                .content(content.toString())
                .tag(event.getType())
                .level(event.getLevel())
                .timestamp(event.getTimestamp())
                .build();
        broadcast(logStreamDTO);
    }

    /**
     * 处理熔断器状态变化事件
     */
    private void handleCircuitBreaker(MonitoringEvents.CircuitBreakerStateEvent event) {
        String content = String.format("CircuitBreaker [%s] %s -> %s (failure rate: %.1f%%)",
                event.getResourceId(), event.getOldState(), event.getNewState(), event.getFailureRate() * 100);

        LogStreamDTO logStreamDTO = LogStreamDTO.builder()
                .type("ALERT")
                .content(content)
                .tag("CIRCUIT_BREAKER")
                .level("WARNING")
                .timestamp(event.getTimestamp())
                .build();
        broadcast(logStreamDTO);
    }

    /**
     * 处理 GC 泄漏检测结果事件
     */
    private void handleLeakDetection(MonitoringEvents.LeakDetectionEvent event) {
        String level = event.isCollected() ? "INFO" : "ERROR";
        long elapsedMillis = Math.max(0L, event.getTimestamp() - event.getTriggerTimeMillis());
        String content = String.format("Ling [%s] version=%s mode=%s %s (+%dms)",
                event.getLingId(),
                event.getVersion(),
                event.getDetectionMode(),
                event.getMessage(),
                elapsedMillis);

        LogStreamDTO logStreamDTO = LogStreamDTO.builder()
                .type("LEAK_DETECTION")
                .lingId(event.getLingId())
                .content(content)
                .tag(event.isCollected() ? "OK" : "FAIL")
                .level(level)
                .timestamp(event.getTimestamp())
                .build();
        broadcast(logStreamDTO);
    }

    private void handleCleanupCapability(MonitoringEvents.ResourceCleanupCapabilityEvent event) {
        String content = String.format("%s capabilities: %s",
                event.getRuntime(),
                event.getSummary());

        LogStreamDTO logStreamDTO = LogStreamDTO.builder()
                .type("RUNTIME_DIAGNOSTIC")
                .content(content)
                .tag("RESOURCE_CLEANUP_CAPABILITY")
                .level("INFO")
                .timestamp(event.getTimestamp())
                .build();
        broadcast(logStreamDTO);
    }

    private String resolveAuditLevel(MonitoringEvents.AuditLogEvent event) {
        if (event.getResult() == null) {
            return "INFO";
        }
        switch (event.getResult()) {
            case ALLOWED:
                return "INFO";
            case DENIED:
                return "WARNING";
            case FAILED:
                return "ERROR";
            default:
                return "INFO";
        }
    }

    /**
     * 处理实例状态变化事件
     */
    private void handleInstanceStateChange(InstanceStateChangedEvent event) {
        String content = String.format("Ling [%s] version=%s: %s -> %s",
                event.getLingId(), event.getVersion(),
                event.getFromStatus(), event.getToStatus());

        String level = "INFO";
        if (event.getToStatus().name().contains("ERROR") || event.getToStatus().name().contains("FAILED")) {
            level = "ERROR";
        } else if (event.getToStatus().name().contains("DEACTIVATED") || event.getToStatus().name().contains("STOPPED")) {
            level = "WARNING";
        }

        LogStreamDTO logStreamDTO = LogStreamDTO.builder()
                .type("ALERT")
                .lingId(event.getLingId())
                .version(event.getVersion())
                .content(content)
                .tag("STATE_CHANGE")
                .level(level)
                .timestamp(event.getTimestamp())
                .build();
        broadcast(logStreamDTO);
    }

    /**
     * 处理运行时状态变化事件
     */
    private void handleRuntimeStateChange(RuntimeStateChangedEvent event) {
        String content = String.format("Ling [%s] runtime: %s -> %s",
                event.getLingId(), event.getFrom(), event.getTo());

        String level = "INFO";
        if (event.getTo().name().contains("ERROR") || event.getTo().name().contains("FAILED")) {
            level = "ERROR";
        } else if (event.getTo().name().contains("STOPPED") || event.getTo().name().contains("INACTIVE")) {
            level = "WARNING";
        }

        LogStreamDTO logStreamDTO = LogStreamDTO.builder()
                .type("ALERT")
                .lingId(event.getLingId())
                .content(content)
                .tag("RUNTIME_CHANGE")
                .level(level)
                .timestamp(event.getTimestamp())
                .build();
        broadcast(logStreamDTO);
    }

    /**
     * 处理实例销毁事件
     */
    private void handleInstanceDestroyed(InstanceDestroyedEvent event) {
        String content = String.format("Ling [%s] version=%s destroyed",
                event.getLingId(), event.getVersion());

        LogStreamDTO logStreamDTO = LogStreamDTO.builder()
                .type("ALERT")
                .lingId(event.getLingId())
                .version(event.getVersion())
                .content(content)
                .tag("INSTANCE_DESTROYED")
                .level("WARNING")
                .timestamp(event.getTimestamp())
                .build();
        broadcast(logStreamDTO);
    }

    /**
     * 处理灵元安装中事件
     */
    private void handleLingInstalling(LingInstallingEvent event) {
        String content = String.format("Ling [%s] version=%s installing...",
                event.getLingId(), event.getVersion());

        LogStreamDTO logStreamDTO = LogStreamDTO.builder()
                .type("ALERT")
                .lingId(event.getLingId())
                .version(event.getVersion())
                .content(content)
                .tag("LING_INSTALLING")
                .level("INFO")
                .timestamp(event.getTimestamp())
                .build();
        broadcast(logStreamDTO);
    }

    /**
     * 处理灵元安装完成事件
     */
    private void handleLingInstalled(LingInstalledEvent event) {
        String content = String.format("Ling [%s] version=%s installed successfully!",
                event.getLingId(), event.getVersion());

        LogStreamDTO logStreamDTO = LogStreamDTO.builder()
                .type("ALERT")
                .lingId(event.getLingId())
                .version(event.getVersion())
                .content(content)
                .tag("LING_INSTALLED")
                .level("INFO")
                .timestamp(event.getTimestamp())
                .build();
        broadcast(logStreamDTO);
    }

    /**
     * 处理灵元卸载中事件
     */
    private void handleLingUninstalling(LingUninstallingEvent event) {
        String content = String.format("Ling [%s] uninstalling...",
                event.getLingId());

        LogStreamDTO logStreamDTO = LogStreamDTO.builder()
                .type("ALERT")
                .lingId(event.getLingId())
                .version(event.getVersion())
                .content(content)
                .tag("LING_UNINSTALLING")
                .level("WARNING")
                .timestamp(event.getTimestamp())
                .build();
        broadcast(logStreamDTO);
    }

    /**
     * 处理灵元卸载完成事件
     */
    private void handleLingUninstalled(LingUninstalledEvent event) {
        String content = String.format("Ling [%s] uninstalled successfully",
                event.getLingId());

        LogStreamDTO logStreamDTO = LogStreamDTO.builder()
                .type("ALERT")
                .lingId(event.getLingId())
                .version(event.getVersion())
                .content(content)
                .tag("LING_UNINSTALLED")
                .level("INFO")
                .timestamp(event.getTimestamp())
                .build();
        broadcast(logStreamDTO);
    }

    /**
     * 广播日志事件给所有 SSE 连接
     *
     * @param logStreamDTO 日志数据传输对象
     */
    public void broadcast(LogStreamDTO logStreamDTO) {
        if (emitters.isEmpty())
            return;
        if (dispatcher.isShutdown()) {
            return;
        }

        // 异步提交给分发线程，不阻塞当前业务线程 (Core Kernel)
        try {
            dispatcher.submit(withCoreClassLoader(() -> {
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
            }));
        } catch (RejectedExecutionException e) {
            // 关闭过程中拒绝提交任务属于正常现象，直接忽略
        }
    }

    /**
     * 发送心跳事件给所有 SSE 连接
     */
    private void sendHeartbeat() {
        if (emitters.isEmpty())
            return;
        if (dispatcher.isShutdown()) {
            return;
        }
        try {
            dispatcher.submit(withCoreClassLoader(() -> {
                List<SseEmitter> dead = new ArrayList<>();
                for (SseEmitter emitter : emitters) {
                    try {
                        emitter.send(SseEmitter.event().name("ping").data("pong"));
                    } catch (Exception e) {
                        dead.add(emitter);
                    }
                }
                emitters.removeAll(dead);
            }));
        } catch (RejectedExecutionException e) {
            // 关闭过程中拒绝提交任务属于正常现象，直接忽略
        }
    }

    /**
     * 移除已关闭的连接
     */
    private void removeEmitter(SseEmitter emitter) {
        emitters.remove(emitter);
        log.debug("SSE connection closed. Active: {}", emitters.size());
    }

    /**
     * 服务销毁时，清理所有 SSE 连接
     */
    @Override
    public void destroy() {
        eventBus.unsubscribeAll("lingframe-dashboard");
        dispatcher.shutdownNow();
        scheduler.shutdownNow();
        emitters.forEach(SseEmitter::complete);
    }

    /**
     * 确保在正确的 ClassLoader 上下文中执行任务
     */
    private Runnable withCoreClassLoader(Runnable task) {
        return () -> {
            ClassLoader prev = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(CORE_CLASSLOADER);
            try {
                task.run();
            } finally {
                Thread.currentThread().setContextClassLoader(prev);
            }
        };
    }
}
