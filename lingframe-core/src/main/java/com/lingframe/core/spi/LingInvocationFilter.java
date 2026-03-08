package com.lingframe.core.spi;

import com.lingframe.core.pipeline.InvocationContext;

/**
 * 灵元调用拦截器接口 (SPI)
 * 允许在调用链的不同阶段注入逻辑（如流量治理、权限检查、审计等）。
 */
public interface LingInvocationFilter {
    /** 获取拦截器执行顺序，值越小优先级越高 */
    int getOrder();

    /**
     * 执行过滤逻辑
     * 
     * @param context 调用上下文
     * @param chain   过滤器链
     * @return 调用结果
     * @throws Throwable 业务异常或 governance 限制抛出的异常
     */
    Object doFilter(InvocationContext context, LingFilterChain chain) throws Throwable;
}
