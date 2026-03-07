package com.lingframe.core.pipeline;

import com.lingframe.core.exception.LingInvocationException;
import com.lingframe.core.spi.LingFilterChain;

public class InvocationPipelineEngine {
    private final FilterRegistry registry;

    public InvocationPipelineEngine(FilterRegistry registry) {
        this.registry = registry;
    }

    public Object invoke(InvocationContext ctx) {
        try {
            LingFilterChain chain = new DefaultFilterChain(registry.getOrderedFilters(), 0);
            return chain.doFilter(ctx);
        } catch (LingInvocationException e) {
            throw e;
        } catch (Throwable e) {
            throw new LingInvocationException(
                    ctx.getServiceFQSID(), LingInvocationException.ErrorKind.INTERNAL_ERROR, e);
        }
    }

    /**
     * 驱逐指定灵元的弹性治理组件。
     * 由灵元卸载链路调用，防止限流器/熔断器内存泄漏。
     */
    public void evictLingResources(String lingId) {
        registry.evictLingResources(lingId);
    }
}
