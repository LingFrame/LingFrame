package com.lingframe.core.pipeline;

import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.kernel.LingInvocationException;
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
}
