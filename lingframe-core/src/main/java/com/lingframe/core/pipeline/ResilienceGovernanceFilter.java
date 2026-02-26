package com.lingframe.core.pipeline;

import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.kernel.LingInvocationException;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;

public class ResilienceGovernanceFilter implements LingInvocationFilter {
    @Override
    public int getOrder() {
        return FilterPhase.RESILIENCE;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        // TODO: M2 阶段平滑迁移旧版 fallback 降级返回、retryCount 重试的循环机制
        // （当前占位，复用旧版现有的限流熔断组件调用，具体实现将在 M2 的具体业务类中对齐）

        try {
            return chain.doFilter(ctx);
        } catch (LingInvocationException e) {
            // 接管并处理限流、熔断场景下的降级逻辑
            throw e;
        }
    }
}
