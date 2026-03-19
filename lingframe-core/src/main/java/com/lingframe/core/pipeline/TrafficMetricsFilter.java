package com.lingframe.core.pipeline;

import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.metrics.LingHealthMetrics;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.api.context.LingCallContext;
import lombok.extern.slf4j.Slf4j;

/**
 * 流量指标过滤器
 * 
 * <p>核心职责：
 * <ul>
 *   <li>全链路追踪 - 记录方法级调用链路，包括入站、出站、异常</li>
 *   <li>指标收集 - 收集请求成功/失败、耗时等健康指标</li>
 *   <li>上下文管理 - 确保 ThreadLocal 与 InvocationContext 的 traceId 一致</li>
 *   <li>运行时管理 - 维护 LingRuntime 的请求计数</li>
 * </ul>
 * 
 * <p>执行顺序：{@link FilterPhase#METRICS} (优先级较低，在业务逻辑之后执行)
 * 
 * <p>追踪事件推送：
 * <ul>
 *   <li>IN - 方法入站 (→ MethodName)</li>
 *   <li>OUT - 方法出站 (← MethodName (costMs))</li>
 *   <li>ERROR - 方法异常 (✗ MethodName (costMs) - ExceptionType)</li>
 * </ul>
 */
@Slf4j
public class TrafficMetricsFilter implements LingInvocationFilter {
    private final LingRepository repository;
    private final MetricsCollector metricsCollector;
    private final EventBus eventBus;

    /**
     * 完整构造器
     * 
     * @param repository 灵元仓库，用于获取运行时
     * @param metricsCollector 指标收集器
     * @param eventBus 事件总线，用于推送追踪事件
     */
    public TrafficMetricsFilter(LingRepository repository, MetricsCollector metricsCollector, EventBus eventBus) {
        this.repository = repository;
        this.metricsCollector = metricsCollector;
        this.eventBus = eventBus;
    }

    /**
     * 简化构造器（无事件推送）
     */
    public TrafficMetricsFilter(LingRepository repository, MetricsCollector metricsCollector) {
        this.repository = repository;
        this.metricsCollector = metricsCollector;
        this.eventBus = null;
    }

    /**
     * 最简构造器（仅追踪，无指标）
     */
    public TrafficMetricsFilter(LingRepository repository) {
        this.repository = repository;
        this.metricsCollector = null;
        this.eventBus = null;
    }

    /**
     * 空构造器（无任何功能）
     */
    public TrafficMetricsFilter() {
        this.repository = null;
        this.metricsCollector = null;
        this.eventBus = null;
    }

    @Override
    public int getOrder() {
        return FilterPhase.METRICS;
    }

    /**
     * 执行过滤逻辑
     * 
     * <p>处理流程：
     * <ol>
     *   <li>同步 traceId 到 ThreadLocal</li>
     *   <li>获取或创建 LingRuntime</li>
     *   <li>增加调用深度</li>
     *   <li>发布入站追踪事件</li>
     *   <li>执行后续过滤器链</li>
     *   <li>发布出站/异常追踪事件</li>
     *   <li>记录指标</li>
     *   <li>减少调用深度</li>
     * </ol>
     */
    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        long start = System.nanoTime();

        // 核心同步逻辑：优先保证 ThreadLocal 上下文与 InvocationContext 一致
        String traceId = ctx.getTraceId();
        if (traceId == null || traceId.isEmpty()) {
            traceId = LingCallContext.startTrace();
            ctx.setTraceId(traceId);
        } else {
            LingCallContext.setTraceId(traceId);
        }

        if (ctx.getCreateTimeNanos() == 0) {
            ctx.setCreateTimeNanos(start);
        }

        // 缓存运行时引用，减少重复查找压力 (复用逻辑遵循铁律 2.0，已在 reset 中处理清理)
        LingRuntime runtime = ctx.getRuntime();
        if (runtime == null && repository != null) {
            String lingId = ctx.getTargetLingId();
            if (lingId == null && ctx.getServiceFQSID() != null) {
                // 尝试从 FQSID (lingId:serviceId) 解析
                String fqsid = ctx.getServiceFQSID();
                int idx = fqsid.indexOf(':');
                if (idx > 0) {
                    lingId = fqsid.substring(0, idx);
                }
            }
            if (lingId != null) {
                runtime = repository.getRuntime(lingId);
                ctx.setRuntime(runtime);
            }
        }

        if (runtime != null) {
            runtime.startRequest();
        }

        String lingId = ctx.getTargetLingId();
        String serviceFQSID = ctx.getServiceFQSID();
        String operation = ctx.getOperation();
        int depth = LingCallContext.getDepth();
        
        LingCallContext.increaseDepth();
        
        publishTrace(traceId, lingId, "→ " + (operation != null ? operation : serviceFQSID), "IN", depth);

        try {
            Object result = chain.doFilter(ctx);
            long costMs = (System.nanoTime() - start) / 1_000_000;
            publishTrace(traceId, lingId, 
                "← " + (operation != null ? operation : serviceFQSID) + " (" + costMs + "ms)", 
                "OUT", depth);
            recordMetrics(ctx, start, true, null);
            return result;
        } catch (Throwable t) {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            publishTrace(traceId, lingId, 
                "✗ " + (operation != null ? operation : serviceFQSID) + " (" + costMs + "ms) - " + t.getClass().getSimpleName(), 
                "ERROR", depth);
            recordMetrics(ctx, start, false, t);
            throw t;
        } finally {
            LingCallContext.decreaseDepth();
            if (runtime != null) {
                runtime.endRequest();
            }
        }
    }

    /**
     * 记录请求指标
     * 
     * @param ctx 调用上下文
     * @param startNanos 开始时间（纳秒）
     * @param success 是否成功
     * @param error 异常（如果有）
     */
    private void recordMetrics(InvocationContext ctx, long startNanos, boolean success, Throwable error) {
        long costMs = (System.nanoTime() - startNanos) / 1_000_000;
        
        if (log.isDebugEnabled()) {
            log.debug("[Ling-Trace] Service={} Cost={}ms Success={}",
                    ctx.getServiceFQSID(), costMs, success);
        }
        
        if (metricsCollector == null) {
            return;
        }
        
        String lingId = extractLingId(ctx);
        if (lingId == null) {
            return;
        }
        
        LingHealthMetrics metrics = metricsCollector.getOrCreate(lingId);
        
        if (success) {
            metrics.recordSuccess(costMs);
        } else {
            boolean isTimeout = isTimeoutError(error);
            metrics.recordFailure(costMs, isTimeout);
        }
    }
    
    /**
     * 从上下文中提取灵元ID
     */
    private String extractLingId(InvocationContext ctx) {
        String lingId = ctx.getTargetLingId();
        if (lingId != null) {
            return lingId;
        }
        
        String fqsid = ctx.getServiceFQSID();
        if (fqsid != null && fqsid.contains(":")) {
            return fqsid.substring(0, fqsid.indexOf(':'));
        }
        
        return null;
    }
    
    /**
     * 判断是否为超时异常
     */
    private boolean isTimeoutError(Throwable error) {
        if (error == null) {
            return false;
        }
        
        String message = error.getMessage();
        if (message != null) {
            message = message.toLowerCase();
            return message.contains("timeout") || message.contains("timed out");
        }
        
        return isTimeoutError(error.getCause());
    }
    
    /**
     * 发布追踪事件到事件总线
     * 
     * @param traceId 追踪ID
     * @param lingId 灵元ID
     * @param action 操作描述（如 "→ OrderService.create"）
     * @param type 类型：IN（入站）、OUT（出站）、ERROR（异常）
     * @param depth 调用深度（用于缩进显示）
     */
    private void publishTrace(String traceId, String lingId, String action, String type, int depth) {
        if (eventBus != null && traceId != null) {
            try {
                eventBus.publish(new MonitoringEvents.TraceLogEvent(traceId, lingId, action, type, depth));
            } catch (Exception e) {
                log.warn("Failed to publish trace event: {}", e.getMessage());
            }
        }
    }
}
