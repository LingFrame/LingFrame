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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
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

    /** 最大 SSE 连接数，防止恶意/异常场景 OOM */
    private static final int MAX_CONNECTIONS = 100;

    /** SSE 连接超时时间：30 分钟，避免死连接永久驻留 */
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    /**
     * 连接许可信号量：原子获取/释放，避免 check-then-act 竞态导致超限。
     * 公平模式（true）避免线程饥饿。
     */
    private final Semaphore connectionSemaphore = new Semaphore(MAX_CONNECTIONS, true);

    /**
     * 已释放许可的 emitter 标记集合：保证每个 emitter 的许可只 release 一次，
     * 避免 onCompletion/onTimeout/onError/broadcast 清理多路径触发导致许可超发。
     * <p>
     * 使用 WeakHashMap 支撑：emitter 从 {@link #emitters} 移除后失去强引用，
     * GC 时自动清除标记条目，避免长期累积导致内存泄漏。
     * 外层 {@link Collections#synchronizedSet(Set)} 保证并发安全。
     */
    private final Set<SseEmitter> released = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    /**
     * 维护所有活跃的 SSE 连接。
     */
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * 事件分发线程池（固定小规模并行）。
     * <p>
     * 用固定 4 线程的小池代替单线程 dispatcher 顺序广播：单个慢/阻塞 emitter
     * 的 send 只占用一个 worker，不拖累其余连接的广播（避免 head-of-line 阻塞）；
     * 同时维持「不抢占业务线程池」。
     * <p>
     * 内存权衡：每 emitter 独立任务意味着高峰事件率 × 慢客户端时队列会积压，
     * 因此使用<b>有界队列</b>（1024）配合 {@link ThreadPoolExecutor.AbortPolicy}：
     * 队列满时提交抛出 {@link RejectedExecutionException}，由调用方（broadcast 系列）
     * 捕获忽略——SSE 日志是尽力而为的观测通道，宁可丢弃本次广播也不允许无界积压 OOM。
     * 队列大小 × 每事件 N 个 emitter 任务的上限由 {@link #MAX_CONNECTIONS}（100）间接封顶。
     */
    private final ExecutorService dispatcher = new ThreadPoolExecutor(
            4, 4, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(1024),
            r -> {
                Thread t = new Thread(r, "ling-sse-dispatcher");
                t.setDaemon(true);
                t.setContextClassLoader(CORE_CLASSLOADER);
                t.setUncaughtExceptionHandler((thread, ex) -> log.error("SSE dispatcher thread error", ex));
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

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
     * <p>并发安全：用 {@link Semaphore#tryAcquire(long, TimeUnit)} 原子获取许可，
     * 避免 check-then-act 竞态导致超限。三个回调（onCompletion/onTimeout/onError）
     * 都会释放许可，防止连接泄漏。
     *
     * @return SSE 发射器实例
     * @throws IllegalStateException 连接数达到上限
     */
    public SseEmitter createEmitter() {
        // 原子获取许可，避免 if(size >= MAX) + add 的竞态超限
        boolean acquired;
        try {
            acquired = connectionSemaphore.tryAcquire(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring SSE connection permit");
        }
        if (!acquired) {
            log.warn("SSE connection rejected: max connections ({}) reached", MAX_CONNECTIONS);
            throw new IllegalStateException("Max SSE connections reached: " + MAX_CONNECTIONS);
        }

        // 设有限超时，避免死连接永久驻留
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // 三个回调都 release 许可并移除 emitter，避免泄漏
        emitter.onCompletion(() -> releaseEmitter(emitter));
        emitter.onTimeout(() -> {
            releaseEmitter(emitter);
            emitter.complete();
        });
        emitter.onError((e) -> releaseEmitter(emitter));

        emitters.add(emitter);

        // 立即发送初始事件，触发 response flush
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (Exception e) {
            // 首次 send 失败说明连接已死：立即释放许可并完成，避免该连接永久占用许可
            log.warn("Failed to send initial SSE event, releasing permit", e);
            releaseEmitter(emitter);
            emitter.complete();
            throw new IllegalStateException("Failed to establish SSE connection", e);
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
        broadcastLingChanged(event.getLingId(), "installed", false);
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
        boolean leaked = event.isClassLoaderLeaked();
        String content = leaked
                ? String.format("Ling [%s] uninstalled with ClassLoader leak warning (verification failed)",
                        event.getLingId())
                : String.format("Ling [%s] uninstalled successfully (ClassLoader reclaimed)", event.getLingId());

        LogStreamDTO logStreamDTO = LogStreamDTO.builder()
                .type("ALERT")
                .lingId(event.getLingId())
                .version(event.getVersion())
                .content(content)
                .tag("LING_UNINSTALLED")
                .level(leaked ? "WARNING" : "INFO")
                .timestamp(event.getTimestamp())
                .build();
        broadcast(logStreamDTO);
        broadcastLingChanged(event.getLingId(), "uninstalled", leaked);
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

        // 异步提交给分发线程池，不阻塞当前业务线程 (Core Kernel)。
        // 每个 emitter 独立提交任务，避免单个慢 emitter 拖累其余连接（head-of-line 阻塞）。
        try {
            for (SseEmitter emitter : emitters) {
                dispatcher.submit(withCoreClassLoader(() -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("log-event")
                                .data(logStreamDTO, MediaType.APPLICATION_JSON));
                    } catch (Exception e) {
                        // send 失败视为连接已死，统一通过 releaseEmitter 释放许可并移除
                        releaseEmitter(emitter);
                    }
                }));
            }
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
            for (SseEmitter emitter : emitters) {
                dispatcher.submit(withCoreClassLoader(() -> {
                    try {
                        emitter.send(SseEmitter.event().name("ping").data("pong"));
                    } catch (Exception e) {
                        // send 失败视为连接已死，统一通过 releaseEmitter 释放许可并移除
                        releaseEmitter(emitter);
                    }
                }));
            }
        } catch (RejectedExecutionException e) {
            // 关闭过程中拒绝提交任务属于正常现象，直接忽略
        }
    }

    /**
     * 广播灵元列表变更事件给所有 SSE 连接，触发前端刷新灵元列表。
     * <p>
     * 在灵元安装完成、或卸载验证（GC 回收确认）通过后调用，替代前端轮询，
     * 保证外部（如 MCP ling_unload 工具）触发的卸载能即时从前端列表消失/出现。
     * 卸载事件携带 leakDetected，前端据此对「卸载完成但 ClassLoader 未回收」给出警告。
     *
     * @param lingId      变更的灵元 id
     * @param action      "installed" 或 "uninstalled"
     * @param leakDetected 卸载验证结论：true 表示 ClassLoader 未回收（仅卸载场景有意义）
     */
    public void broadcastLingChanged(String lingId, String action, boolean leakDetected) {
        if (emitters.isEmpty())
            return;
        if (dispatcher.isShutdown()) {
            return;
        }
        Map<String, Object> payload = new HashMap<>(4);
        payload.put("lingId", lingId);
        payload.put("action", action);
        payload.put("leakDetected", leakDetected);
        try {
            for (SseEmitter emitter : emitters) {
                dispatcher.submit(withCoreClassLoader(() -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("ling-changed")
                                .data(payload, MediaType.APPLICATION_JSON));
                    } catch (Exception e) {
                        releaseEmitter(emitter);
                    }
                }));
            }
        } catch (RejectedExecutionException e) {
            // 关闭过程中拒绝提交任务属于正常现象，直接忽略
        }
    }

    /**
     * 释放 SSE 连接：归还许可并从活跃列表移除。
     *
     * <p>用 {@link Set#add(Object)} 的返回值保证每个 emitter 的许可只 release 一次，
     * 避免 onCompletion/onTimeout/onError 以及 broadcast 清理多路径触发导致许可超发。
     * emitter 失去强引用后由 WeakHashMap 自动清除，无需手动移除标记。
     */
    private void releaseEmitter(SseEmitter emitter) {
        if (released.add(emitter)) {
            connectionSemaphore.release();
        }
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
        try {
            if (!dispatcher.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("SSE dispatcher did not terminate within 5s");
            }
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("SSE scheduler did not terminate within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
