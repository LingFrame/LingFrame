package com.lingframe.core.pipeline;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.spi.LingInvocationFilter;
import com.lingframe.core.spi.TrafficRouter;

import java.util.*;

/**
 * Filter 注册表。
 * <p>
 * 职责：
 * 1. 在构造时注册所有内置 Filter，并注入它们的依赖
 * 2. 允许 SPI / Spring 动态注入扩展 Filter
 * 3. 提供按 order 排好序的不可变 Filter 链（缓存，避免每次调用排序）
 */
public class FilterRegistry {
    private final List<LingInvocationFilter> builtinFilters = new ArrayList<>();
    private final List<LingInvocationFilter> spiFilters = new ArrayList<>();
    private final InvokableMethodCache methodCache;

    // 保留弹性治理 Filter 引用，支持灵元卸载时驱逐弹性组件
    private ResilienceGovernanceFilter resilienceFilter;

    // 排序缓存，dirty 时重建
    private volatile List<LingInvocationFilter> orderedCache;

    public FilterRegistry(InvokableMethodCache methodCache) {
        this.methodCache = methodCache;
    }

    /**
     * 初始化内置 Filter 并注入依赖。
     * 必须在 Pipeline 可用前调用一次。
     *
     * @param lingRepository 运行时仓库，供 StateGuard / Routing 查询
     * @param trafficRouter  路由策略（可为 null，此时灰度路由走默认 LatestVersionPolicy）
     */
    public void initialize(LingRepository lingRepository, TrafficRouter trafficRouter, EventBus eventBus) {
        MacroStateGuardFilter stateGuard = new MacroStateGuardFilter(lingRepository);
        CanaryRoutingFilter routing = new CanaryRoutingFilter(
                lingRepository,
                trafficRouter != null ? trafficRouter : new LatestVersionPolicy());
        ResilienceGovernanceFilter resilience = new ResilienceGovernanceFilter(lingRepository, eventBus);
        this.resilienceFilter = resilience;
        ContextIsolationFilter isolation = new ContextIsolationFilter();
        TerminalInvokerFilter terminal = new TerminalInvokerFilter(methodCache);

        builtinFilters.addAll(Arrays.asList(
                new TrafficMetricsFilter(),
                stateGuard,
                routing,
                resilience,
                isolation,
                terminal));

        invalidateCache();
    }

    /**
     * 从宿主 ClassLoader 加载 SPI 扩展 Filter
     */
    public void loadSpiFilters(ClassLoader hostClassLoader) {
        for (LingInvocationFilter filter : ServiceLoader.load(LingInvocationFilter.class, hostClassLoader)) {
            spiFilters.add(filter);
        }
        invalidateCache();
    }

    /**
     * 允许外部框架（如 Spring Starter）动态注入托管的 Filter
     */
    public void addDynamicFilter(LingInvocationFilter filter) {
        this.spiFilters.add(filter);
        invalidateCache();
    }

    public List<LingInvocationFilter> getOrderedFilters() {
        List<LingInvocationFilter> cached = orderedCache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (orderedCache != null) {
                return orderedCache;
            }
            List<LingInvocationFilter> all = new ArrayList<>();
            all.addAll(builtinFilters);
            all.addAll(spiFilters);
            all.sort(Comparator.comparingInt(LingInvocationFilter::getOrder));
            orderedCache = Collections.unmodifiableList(all);
            return orderedCache;
        }
    }

    private void invalidateCache() {
        this.orderedCache = null;
    }

    /**
     * 驱逐指定灵元的弹性治理组件（限流器、熔断器）。
     * 由灵元卸载链路调用，防止内存泄漏。
     */
    public void evictResilience(String lingId) {
        if (resilienceFilter != null) {
            resilienceFilter.evict(lingId);
        }
    }
}
