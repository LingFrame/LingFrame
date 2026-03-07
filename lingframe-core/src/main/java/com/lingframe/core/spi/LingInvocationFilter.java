package com.lingframe.core.spi;

import com.lingframe.core.pipeline.InvocationContext;

public interface LingInvocationFilter {
    int getOrder();

    Object doFilter(InvocationContext context, LingFilterChain chain) throws Throwable;
}
