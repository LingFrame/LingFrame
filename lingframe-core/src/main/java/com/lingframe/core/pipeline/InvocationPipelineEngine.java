package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.spi.LingFilterChain;

/**
 * 灵元调用管道引擎
 * 负责组装并执行拦截器链 (LingInvocationFilter Chain)，实现权限校验、流量治理、路由转发等全链路逻辑。
 */
public class InvocationPipelineEngine {
    private final FilterRegistry registry;

    public InvocationPipelineEngine(FilterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 执行灵元服务调用
     * 
     * @param ctx 调用上下文，包含 FQSID、参数、追踪 ID 等信息
     * @return 调用结果（从 TerminalInvokerFilter 返回）
     * @throws LingInvocationException 当链路任何环节发生异常或治理拒绝时抛出
     */
    public Object invoke(InvocationContext ctx) {
        // 将上下文挂载为当前线程活跃上下文，使 Pipeline 内部（含 wrap() 跨线程传播）可通过 current() 发现
        InvocationContext prev = ctx.attach();
        try {
            LingFilterChain chain = new DefaultFilterChain(registry.getOrderedFilters(), 0);
            return chain.doFilter(ctx);
        } catch (LingInvocationException e) {
            throw e;
        } catch (Throwable e) {
            throw new LingInvocationException(
                    ctx.getServiceFQSID(), LingInvocationException.ErrorKind.INTERNAL_ERROR, e);
        } finally {
            InvocationContext.detach(prev);
        }
    }

    /**
     * 驱逐指定灵元的弹性治理组件。
     * 由灵元卸载链路调用，防止限流器/熔断器内存泄漏。
     */
    public void evictLingResources(String lingId) {
        registry.evictLingResources(lingId);
    }

    public int evictMethodCache(String lingId) {
        return registry.evictMethodCache(lingId);
    }
}
