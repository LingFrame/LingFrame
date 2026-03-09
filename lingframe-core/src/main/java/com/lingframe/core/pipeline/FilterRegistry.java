package com.lingframe.core.pipeline;

import com.lingframe.api.security.PermissionService;
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
    private ThreadIsolationGovernanceFilter isolationFilter;

    // 排序缓存，dirty 时重建
    private volatile List<LingInvocationFilter> orderedCache;

    private final PermissionService permissionService;

    public FilterRegistry(InvokableMethodCache methodCache, PermissionService permissionService) {
        this.methodCache = methodCache;
        this.permissionService = permissionService;
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

        ThreadIsolationGovernanceFilter threadIsolation = new ThreadIsolationGovernanceFilter(lingRepository);
        this.isolationFilter = threadIsolation;

        PermissionGovernanceFilter permissionGovernance = new PermissionGovernanceFilter(permissionService);

        ContextIsolationFilter isolation = new ContextIsolationFilter();
        TerminalInvokerFilter terminal = new TerminalInvokerFilter(methodCache);

        builtinFilters.add(new TrafficMetricsFilter(lingRepository));
        builtinFilters.add(stateGuard);
        builtinFilters.add(routing);
        builtinFilters.add(resilience);
        builtinFilters.add(threadIsolation);
        builtinFilters.add(permissionGovernance);
        builtinFilters.add(isolation);
        builtinFilters.add(terminal);

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
     * 驱逐指定灵元的弹性治理组件（限流器、熔断器）和隔离线程。
     * 由灵元卸载链路调用，防止内存泄漏。
     */
    public void evictLingResources(String lingId) {
        if (resilienceFilter != null) {
            resilienceFilter.evict(lingId);
        }
        if (isolationFilter != null) {
            isolationFilter.evict(lingId);
        }
    }
}
