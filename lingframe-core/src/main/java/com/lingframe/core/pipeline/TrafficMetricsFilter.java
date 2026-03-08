package com.lingframe.core.pipeline;

import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import lombok.extern.slf4j.Slf4j;
import java.util.UUID;

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
        if (ctx.getTraceId() == null) {
            ctx.setTraceId(UUID.randomUUID().toString().replace("-", ""));
        }
        if (ctx.getCreateTimeNanos() == 0) {
            ctx.setCreateTimeNanos(start);
        }

        // 缓存运行时引用，减少重复查找压力 (复用逻辑遵循铁律 2.0，已在 reset 中处理清理)
        LingRuntime runtime = ctx.getRuntime();
        if (runtime == null && repository != null && ctx.getTargetLingId() != null) {
            runtime = repository.getRuntime(ctx.getTargetLingId());
            ctx.setRuntime(runtime);
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
