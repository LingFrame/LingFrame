package com.lingframe.core.pipeline;

import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;

/**
 * 基于数组游标的过滤器链。
 * <p>
 * 构造时将过滤器列表转为数组，通过 index 游标推进。
 * 相比原 List 实现，数组直接下标访问避免了 List.get() 的开销，
 * 在高 QPS 下可减少方法调用开销。
 */
public class DefaultFilterChain implements LingFilterChain {
    private final LingInvocationFilter[] filters;
    private final int index;

    public DefaultFilterChain(java.util.List<LingInvocationFilter> filters) {
        this.filters = filters.toArray(new LingInvocationFilter[0]);
        this.index = 0;
    }

    private DefaultFilterChain(LingInvocationFilter[] filters, int index) {
        this.filters = filters;
        this.index = index;
    }

    @Override
    public Object doFilter(InvocationContext ctx) throws Throwable {
        if (index >= filters.length) {
            throw new IllegalStateException("Filter chain exhausted without terminal invoker");
        }
        return filters[index].doFilter(ctx, new DefaultFilterChain(filters, index + 1));
    }
}
