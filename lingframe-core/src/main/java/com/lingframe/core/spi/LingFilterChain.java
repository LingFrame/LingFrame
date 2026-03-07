package com.lingframe.core.spi;

import com.lingframe.core.pipeline.InvocationContext;

public interface LingFilterChain {
    Object doFilter(InvocationContext context) throws Throwable;
}
