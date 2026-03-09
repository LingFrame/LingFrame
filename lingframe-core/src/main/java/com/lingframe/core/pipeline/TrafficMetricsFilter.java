package com.lingframe.core.pipeline;

import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import com.lingframe.core.monitor.TraceContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TrafficMetricsFilter implements LingInvocationFilter {
    private final LingRepository repository;

    public TrafficMetricsFilter(LingRepository repository) {
        this.repository = repository;
    }

    public TrafficMetricsFilter() {
        this.repository = null;
    }

    @Override
    public int getOrder() {
        return FilterPhase.METRICS;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        long start = System.nanoTime();

        // 核心同步逻辑：优先保证 ThreadLocal 上下文与 InvocationContext 一致
        String traceId = ctx.getTraceId();
        if (traceId == null || traceId.isEmpty()) {
            traceId = TraceContext.start();
            ctx.setTraceId(traceId);
        } else {
            TraceContext.setTraceId(traceId);
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

        try {
            Object result = chain.doFilter(ctx);
            recordMetrics(ctx, start, true, null);
            return result;
        } catch (Throwable t) {
            recordMetrics(ctx, start, false, t);
            throw t;
        } finally {
            if (runtime != null) {
                runtime.endRequest();
            }
        }
    }

    private void recordMetrics(InvocationContext ctx, long startNanos, boolean success, Throwable error) {
        long costMs = (System.nanoTime() - startNanos) / 1000000;
        if (log.isDebugEnabled()) {
            log.debug("[Ling-Trace] Service={} Cost={}ms Success={}",
                    ctx.getServiceFQSID(), costMs, success);
        }
        // 未来的 MetricsCollector 对接点
    }
}
