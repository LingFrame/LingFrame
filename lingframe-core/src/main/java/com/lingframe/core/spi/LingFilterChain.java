package com.lingframe.core.spi;

import com.lingframe.core.pipeline.InvocationContext;

/**
 * 灵元过滤器链接口
 * 驱动调用上下文在不同的拦截器之间传递。
 */
public interface LingFilterChain {
    /**
     * 继续执行过滤器链中的下一个拦截器
     * 
     * @param context 调用上下文
     * @return 执行结果
     * @throws Throwable 异常信息
     */
    Object doFilter(InvocationContext context) throws Throwable;
}
