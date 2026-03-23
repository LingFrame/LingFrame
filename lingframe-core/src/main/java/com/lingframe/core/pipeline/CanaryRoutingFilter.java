package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.model.EngineTrace;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import com.lingframe.core.spi.TrafficRouter;

import java.util.List;

/**
 * 路由过滤器。
 * 负责从 READY 实例中选出目标实例，并写入显式路由分区。
 * ⚠️ 路由阶段只解决“去哪个实例”，不负责方法解析，也不负责权限裁决。
 */
public class CanaryRoutingFilter implements LingInvocationFilter {

    private final LingRepository lingRepository;
    private final TrafficRouter trafficRouter;

    public CanaryRoutingFilter(LingRepository lingRepository, TrafficRouter trafficRouter) {
        this.lingRepository = lingRepository;
        this.trafficRouter = trafficRouter;
    }

    @Override
    public int getOrder() {
        return FilterPhase.ROUTING;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        if (ctx.routing().getTargetInstance() != null) {
            // 入口已经显式指定了目标实例，此时只补记一个“预解析”标记，避免二次路由覆盖入口意图
            ctx.routing().setPreResolved(true);
            return chain.doFilter(ctx);
        }

        String fqsid = ctx.getServiceFQSID();
        if (fqsid == null || trafficRouter == null || lingRepository == null) {
            return chain.doFilter(ctx);
        }

        String lingId = extractLingId(fqsid);
        LingRuntime runtime = resolveRuntime(ctx, lingId);
        if (runtime == null) {
            if (ctx.isGovernOnly()) {
                // GOVERN_ONLY 允许灵核入口只借道治理，不强依赖真实的灵元路由结果
                return chain.doFilter(ctx);
            }
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.ROUTE_FAILURE);
        }

        List<LingInstance> candidates = runtime.getReadyInstances();
        if (candidates.isEmpty()) {
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.ROUTE_FAILURE);
        }

        LingInstance target = trafficRouter.route(candidates, ctx);
        if (target == null) {
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.ROUTE_FAILURE);
        }

        ctx.routing().setTargetInstance(target);
        ctx.setTargetLingId(target.getLingId());
        ctx.setTargetVersion(target.getVersion());

        if (ctx.isSimulation() || ctx.isShouldAudit()) {
            // 模拟和强审计场景下保留选路轨迹，方便解释“为什么命中了这个版本”
            boolean canary = target != runtime.getInstancePool().getDefault();
            String routeType = canary ? "CANARY" : "STABLE";
            ctx.addTrace(EngineTrace.builder()
                    .source("CanaryRoutingFilter")
                    .action("Route selected version " + target.getVersion() + " [" + routeType + "]")
                    .type(routeType)
                    .depth(2)
                    .build());
        }

        return chain.doFilter(ctx);
    }

    private LingRuntime resolveRuntime(InvocationContext ctx, String lingId) {
        LingRuntime runtime = ctx.getRuntime();
        if (runtime != null) {
            return runtime;
        }
        LingRuntime resolved = lingRepository.getRuntime(lingId);
        if (resolved != null) {
            ctx.setRuntime(resolved);
        }
        return resolved;
    }

    private String extractLingId(String fqsid) {
        int separator = fqsid.indexOf(':');
        return separator > 0 ? fqsid.substring(0, separator) : fqsid;
    }
}
