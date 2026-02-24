package com.lingframe.core.spi;

import com.lingframe.core.kernel.InvocationContext;

public interface LingInvocationFilter {
    int getOrder();

    Object doFilter(InvocationContext context, LingFilterChain chain) throws Throwable;
}
