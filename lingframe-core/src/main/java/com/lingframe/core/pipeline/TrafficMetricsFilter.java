package com.lingframe.core.pipeline;

import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import lombok.extern.slf4j.Slf4j;
import java.util.UUID;

@Slf4j
public class TrafficMetricsFilter implements LingInvocationFilter {

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
        ctx.setCreateTimeNanos(start);

        try {
            Object result = chain.doFilter(ctx);
            recordMetrics(ctx, start, true, null);
            return result;
        } catch (Throwable t) {
            recordMetrics(ctx, start, false, t);
            throw t;
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
