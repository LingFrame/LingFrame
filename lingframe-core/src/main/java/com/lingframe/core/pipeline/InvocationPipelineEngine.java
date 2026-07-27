package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.metrics.ProviderMetricsCollector;
import com.lingframe.core.spi.LingFilterChain;

/**
 * 灵元调用 Pipeline 引擎。
 * 负责组装并执行拦截器链（LingInvocationFilter Chain），实现权限校验、流量治理、路由转发等全链路逻辑。
 */
public class InvocationPipelineEngine {
    private final FilterRegistry registry;
    /** Provider 维度指标收集器（可选，null 时不埋点） */
    private final ProviderMetricsCollector providerMetricsCollector;

    public InvocationPipelineEngine(FilterRegistry registry) {
        this(registry, null);
    }

    public InvocationPipelineEngine(FilterRegistry registry, ProviderMetricsCollector providerMetricsCollector) {
        this.registry = registry;
        this.providerMetricsCollector = providerMetricsCollector;
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
        long startTime = providerMetricsCollector != null ? System.currentTimeMillis() : 0L;
        boolean success = false;
        try {
            LingFilterChain chain = new DefaultFilterChain(registry.getOrderedFilters());
            Object result = chain.doFilter(ctx);
            success = true;
            return result;
        } catch (LingInvocationException e) {
            throw e;
        } catch (Error e) {
            // Error（OOM / StackOverflow 等）代表 JVM 即将崩溃，必须透传，
            // 不能包装成 INTERNAL_ERROR 继续执行，否则可能把数据写坏或掩盖崩溃事实。
            throw e;
        } catch (Throwable e) {
            throw new LingInvocationException(
                    ctx.getServiceFQSID(), LingInvocationException.ErrorKind.INTERNAL_ERROR, e);
        } finally {
            if (providerMetricsCollector != null) {
                recordProviderMetrics(ctx, success, System.currentTimeMillis() - startTime);
            }
            InvocationContext.detach(prev);
        }
    }

    /**
     * 记录 provider 维度调用指标。
     * <p>
     * 按 contractId × lingId 二维统计调用量和延迟。
     * contractId 取 FQSID 冒号后部分；裸 contractId 场景下取 FQSID 本身。
     */
    private void recordProviderMetrics(InvocationContext ctx, boolean success, long durationMs) {
        String lingId = ctx.getTargetLingId();
        String contractId = extractContractId(ctx.getServiceFQSID());
        if (contractId == null || lingId == null) {
            return;
        }
        providerMetricsCollector.recordInvocation(contractId, lingId, success, durationMs);
    }

    /**
     * 从 FQSID 提取 contractId。
     * 旧格式 {@code lingId:serviceName} → 取冒号后部分；
     * 裸 contractId（无冒号）→ 取 FQSID 本身。
     */
    private String extractContractId(String fqsid) {
        if (fqsid == null) {
            return null;
        }
        int idx = fqsid.indexOf(':');
        return idx > 0 && idx < fqsid.length() - 1 ? fqsid.substring(idx + 1) : fqsid;
    }

    /**
     * 驱逐指定灵元的弹性治理组件。
     * 由灵元卸载链路调用，防止限流器/熔断器内存泄漏。
     */
    public void evictLingResources(String lingId) {
        registry.evictLingResources(lingId);
    }

    /**
     * 受控恢复时重置与该灵元绑定的治理状态。
     */
    public boolean recoverLingGovernance(String lingId) {
        return registry != null && registry.recoverLingGovernance(lingId);
    }

    public int evictMethodCache(String lingId) {
        return registry.evictMethodCache(lingId);
    }

    /**
     * 按完整前缀驱逐方法句柄缓存，用于版本级精确清理。
     *
     * @param prefix 缓存 key 前缀，例如 "lingId:version@"
     * @return 被驱逐的条目数
     */
    public int evictMethodCacheByPrefix(String prefix) {
        return registry.evictMethodCacheByPrefix(prefix);
    }
}
