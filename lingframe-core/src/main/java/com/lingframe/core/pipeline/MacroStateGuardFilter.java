package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.model.EngineTrace;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;

/**
 * 宏观运行时状态守卫过滤器。
 * 负责在路由之前先回答一个更基础的问题：这个 Runtime 现在到底还能不能接单。
 */
public class MacroStateGuardFilter implements LingInvocationFilter {

    private final LingRepository lingRepository;

    public MacroStateGuardFilter(LingRepository lingRepository) {
        this.lingRepository = lingRepository;
    }

    @Override
    public int getOrder() {
        return FilterPhase.STATE_GUARD;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        String fqsid = ctx.getServiceFQSID();
        if (fqsid == null || lingRepository == null) {
            return chain.doFilter(ctx);
        }

        String lingId = ctx.getLingIdFromFqsid();
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            if (ctx.isGovernOnly()) {
                // GOVERN_ONLY 允许灵核入口借道治理，即便此时并不存在真实灵元 Runtime
                return chain.doFilter(ctx);
            }
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.ROUTE_FAILURE,
                    "Target ling not found: " + lingId);
        }

        RuntimeStatus status = runtime.currentStatus();
        switch (status) {
            case ACTIVE:
            case DEGRADED:
                if (ctx.isSimulation() || ctx.isShouldAudit()) {
                    ctx.addTrace(EngineTrace.builder()
                            .source("MacroStateGuardFilter")
                            .action("Runtime state ready [" + status + "]")
                            .type("OK")
                            .depth(1)
                            .build());
                }
                return chain.doFilter(ctx);
            case INACTIVE:
            case REMOVED:
                String unavailableMessage = "Ling [" + lingId + "] is " + status;
                if (ctx.isSimulation()) {
                    ctx.addTrace(EngineTrace.builder()
                            .source("MacroStateGuardFilter")
                            .action("Simulation blocked because " + unavailableMessage)
                            .type("ERROR")
                            .depth(1)
                            .build());
                }
                throw new LingInvocationException(fqsid,
                        LingInvocationException.ErrorKind.ROUTE_FAILURE, unavailableMessage);
            case STOPPING:
                String stoppingMessage = "Ling [" + lingId + "] is stopping";
                if (ctx.isSimulation()) {
                    ctx.addTrace(EngineTrace.builder()
                            .source("MacroStateGuardFilter")
                            .action("Simulation blocked because " + stoppingMessage)
                            .type("WARN")
                            .depth(1)
                            .build());
                }
                throw new LingInvocationException(fqsid,
                        LingInvocationException.ErrorKind.STATE_REJECTED, stoppingMessage);
            case RECOVERING:
                String recoveringMessage = "Ling [" + lingId + "] is recovering";
                if (ctx.isSimulation()) {
                    ctx.addTrace(EngineTrace.builder()
                            .source("MacroStateGuardFilter")
                            .action("Simulation blocked because " + recoveringMessage)
                            .type("WARN")
                            .depth(1)
                            .build());
                }
                throw new LingInvocationException(fqsid,
                        LingInvocationException.ErrorKind.STATE_REJECTED, recoveringMessage);
            default:
                throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.STATE_REJECTED);
        }
    }
}
