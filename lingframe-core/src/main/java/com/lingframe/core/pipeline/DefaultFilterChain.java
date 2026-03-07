package com.lingframe.core.pipeline;

import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import java.util.List;

public class DefaultFilterChain implements LingFilterChain {
    private final List<LingInvocationFilter> filters;
    private final int index;

    public DefaultFilterChain(List<LingInvocationFilter> filters, int index) {
        this.filters = filters;
        this.index = index;
    }

    @Override
    public Object doFilter(InvocationContext ctx) throws Throwable {
        if (index >= filters.size()) {
            throw new IllegalStateException("Filter chain exhausted without terminal invoker");
        }
        return filters.get(index).doFilter(ctx, new DefaultFilterChain(filters, index + 1));
    }
}
