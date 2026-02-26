package com.lingframe.core.pipeline;

import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.kernel.LingInvocationException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;

import java.util.List;

/**
 * 灰度路由 Filter。
 * 根据 RoutingPolicy 从 READY 实例中选择目标，写入 attachments。
 */
public class CanaryRoutingFilter implements LingInvocationFilter {
    private RoutingPolicy policy;
    private LingRepository lingRepository;

    public void setRoutingPolicy(RoutingPolicy policy) {
        this.policy = policy;
    }

    public void setLingRepository(LingRepository lingRepository) {
        this.lingRepository = lingRepository;
    }

    @Override
    public int getOrder() {
        return FilterPhase.ROUTING;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        // 如果上游已预设目标实例（如模拟场景），直接放行
        if (ctx.getAttachments().containsKey("ling.target.instance")) {
            return chain.doFilter(ctx);
        }

        String fqsid = ctx.getServiceFQSID();
        if (fqsid == null || policy == null || lingRepository == null) {
            return chain.doFilter(ctx);
        }

        String lingId = fqsid.split(":")[0];
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.ROUTE_FAILURE);
        }

        List<LingInstance> candidates = runtime.getReadyInstances();

        if (candidates.isEmpty()) {
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.ROUTE_FAILURE);
        }

        LingInstance target = policy.select(ctx, candidates);
        if (target == null) {
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.ROUTE_FAILURE);
        }

        ctx.setTargetLingId(target.getLingId());
        ctx.setTargetVersion(target.getVersion());

        // 瞬态附件，留给 ContextIsolationFilter 和 TerminalInvokerFilter 取用
        ctx.getAttachments().put("ling.target.instance", target);

        return chain.doFilter(ctx);
    }
}
