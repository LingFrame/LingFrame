package com.lingframe.core.pipeline;

import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.model.EngineTrace;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;

/**
 * 宏观状态守卫 Filter。
 * 根据 RuntimeStatus 拒绝 INACTIVE / STOPPING / REMOVED 状态的灵元请求。
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
            return chain.doFilter(ctx); // 非服务调用或未就绪直接放行
        }

        String lingId = fqsid.split(":")[0];
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.ROUTE_FAILURE);
        }

        RuntimeStatus status = runtime.currentStatus();

        switch (status) {
            case ACTIVE:
            case DEGRADED:
                if (ctx.isDryRun() || ctx.isShouldAudit()) {
                    ctx.addTrace(EngineTrace.builder()
                            .source("MacroStateGuardFilter")
                            .action("State ready [" + status + "], permitted")
                            .type("OK")
                            .depth(1)
                            .build());
                }
                return chain.doFilter(ctx);
            case INACTIVE:
            case REMOVED:
                if (ctx.isDryRun()) {
                    ctx.addTrace(EngineTrace.builder()
                            .source("MacroStateGuardFilter")
                            .action("🛡️ [Dry run blocked] Ling not active or removed [" + status + "]")
                            .type("ERROR")
                            .depth(1)
                            .build());
                    throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.ROUTE_FAILURE);
                }
                throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.ROUTE_FAILURE);
            case STOPPING:
                if (ctx.isDryRun()) {
                    ctx.addTrace(EngineTrace.builder()
                            .source("MacroStateGuardFilter")
                            .action("🛡️ [Dry run blocked] Ling stopping [" + status + "]")
                            .type("WARN")
                            .depth(1)
                            .build());
                    throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.STATE_REJECTED);
                }
                throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.STATE_REJECTED);
            default:
                throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.STATE_REJECTED);
        }
    }
}
