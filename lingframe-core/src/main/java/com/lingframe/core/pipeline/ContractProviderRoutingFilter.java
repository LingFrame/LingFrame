package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.ProviderDescriptor;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.router.ProviderWeightRouter;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import com.lingframe.core.spi.RoutableTarget;

import java.util.List;

/**
 * L0 provider 级路由过滤器。
 * <p>
 * 在 Pipeline 最早阶段（{@link FilterPhase#PROVIDER_ROUTING}）执行，
 * 从 {@code __provider__:} 前缀的 FQSID 中提取契约 ID，
 * 按 provider 权重选择目标 lingId 并设置 {@code ctx.runtime}，
 * 让后续过滤器直接使用已解析的 runtime。
 * <p>
 * 兼容性：旧格式 FQSID（{@code lingId:serviceName}）不触发此阶段，直接放行，
 * 由 {@link CanaryRoutingFilter} 按 lingId 从 FQSID 提取并路由。
 */
public class ContractProviderRoutingFilter implements LingInvocationFilter {

    /** 契约级路由 FQSID 前缀，新格式：{@code __provider__:contractId} */
    public static final String PROVIDER_FQSID_PREFIX = "__provider__:";

    private final LingServiceRegistry lingServiceRegistry;
    private final LingRepository lingRepository;
    private final ProviderWeightRouter providerWeightRouter;

    public ContractProviderRoutingFilter(LingServiceRegistry lingServiceRegistry,
            LingRepository lingRepository, ProviderWeightRouter providerWeightRouter) {
        this.lingServiceRegistry = lingServiceRegistry;
        this.lingRepository = lingRepository;
        this.providerWeightRouter = providerWeightRouter;
    }

    @Override
    public int getOrder() {
        return FilterPhase.PROVIDER_ROUTING;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        String fqsid = ctx.getServiceFQSID();
        if (fqsid == null || !fqsid.startsWith(PROVIDER_FQSID_PREFIX)) {
            // 旧格式 FQSID（lingId:serviceName）走兼容路径
            return chain.doFilter(ctx);
        }

        String contractId = fqsid.substring(PROVIDER_FQSID_PREFIX.length());
        if (lingServiceRegistry == null) {
            // 无 serviceRegistry（native/test 场景），无法做契约级路由，放行
            return chain.doFilter(ctx);
        }
        List<ProviderDescriptor> providers = lingServiceRegistry.getProvidersByContractId(contractId);
        if (providers == null || providers.isEmpty()) {
            // 容错：契约未注册到 provider 索引，放行让后续过滤器处理
            return chain.doFilter(ctx);
        }

        ProviderDescriptor selected = providerWeightRouter.selectProvider(providers, ctx);
        if (selected == null) {
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.ROUTE_FAILURE);
        }

        ctx.setTargetLingId(selected.getLingId());

        RoutableTarget target = lingRepository.getRoutableTarget(selected.getLingId());
        if (target == null) {
            throw new LingInvocationException(fqsid, LingInvocationException.ErrorKind.ROUTE_FAILURE);
        }
        ctx.setRuntime(target);

        // provider 类型埋点：记录选中 provider 的类型，供观测区分流量来源
        ctx.routing().setProviderKind(selected.getKind());

        return chain.doFilter(ctx);
    }
}
