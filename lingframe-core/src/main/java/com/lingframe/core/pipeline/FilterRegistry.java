package com.lingframe.core.pipeline;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.spi.LingInvocationFilter;
import com.lingframe.core.spi.LingServiceInvoker;
import com.lingframe.core.spi.TrafficRouter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * 过滤器注册表。
 * 负责组装内建过滤器、加载扩展过滤器，并在启动阶段校验核心 phase 契约。
 * <p>
 * ⚠️ 这里不是简单地“把过滤器按 order 排个序”。
 * 对灵珑这种纯技术内核来说，Pipeline 本身就是架构的一部分：
 * 哪个阶段能读什么、能写什么、依赖谁先完成，必须在启动时 fail-fast 校验出来，
 * 不能等线上流量进来再靠异常堆栈反推是哪个过滤器越界了。
 */
public class FilterRegistry {

    private final List<LingInvocationFilter> builtinFilters = new ArrayList<>();
    private final List<LingInvocationFilter> spiFilters = new ArrayList<>();
    private final InvokableMethodCache methodCache;
    private final PermissionService permissionService;
    private final LingServiceInvoker serviceInvoker;
    private final GovernanceArbitrator governanceArbitrator;

    private ResilienceGovernanceFilter resilienceFilter;
    private ThreadIsolationGovernanceFilter isolationFilter;
    private volatile List<LingInvocationFilter> orderedCache;

    public FilterRegistry(InvokableMethodCache methodCache, PermissionService permissionService) {
        this(methodCache, permissionService, null, null);
    }

    public FilterRegistry(InvokableMethodCache methodCache, PermissionService permissionService,
            LingServiceInvoker serviceInvoker) {
        this(methodCache, permissionService, serviceInvoker, null);
    }

    public FilterRegistry(InvokableMethodCache methodCache, PermissionService permissionService,
            LingServiceInvoker serviceInvoker, GovernanceArbitrator governanceArbitrator) {
        this.methodCache = methodCache;
        this.permissionService = permissionService;
        this.serviceInvoker = serviceInvoker;
        this.governanceArbitrator = governanceArbitrator;
    }

    public void initialize(LingRepository lingRepository, TrafficRouter trafficRouter, EventBus eventBus) {
        initialize(lingRepository, trafficRouter, eventBus, null, null, null);
    }

    public void initialize(LingRepository lingRepository, TrafficRouter trafficRouter, EventBus eventBus,
            MetricsCollector metricsCollector,
            RuntimeCoordinator runtimeCoordinator, GovernanceMetricsCollector governanceMetricsCollector) {
        initializeInternal(lingRepository, trafficRouter, eventBus, metricsCollector, runtimeCoordinator, governanceMetricsCollector);
    }

    /**
     * 初始化内建过滤器。
     */
    public void initialize(LingRepository lingRepository, TrafficRouter trafficRouter, EventBus eventBus,
            RuntimeCoordinator runtimeCoordinator) {
        initializeInternal(lingRepository, trafficRouter, eventBus, null, runtimeCoordinator, null);
    }

    private void initializeInternal(LingRepository lingRepository, TrafficRouter trafficRouter, EventBus eventBus,
            MetricsCollector metricsCollector,
            RuntimeCoordinator runtimeCoordinator, GovernanceMetricsCollector governanceMetricsCollector) {
        // ⚠️ 内建过滤器的顺序不是偶然结果，而是“事实 -> 决策 -> 执行”的固定协议。
        builtinFilters.clear();

        MacroStateGuardFilter stateGuard = new MacroStateGuardFilter(lingRepository);
        CanaryRoutingFilter routing = new CanaryRoutingFilter(
                lingRepository,
                trafficRouter != null ? trafficRouter : new LatestVersionPolicy());
        ResilienceGovernanceFilter resilience = new ResilienceGovernanceFilter(
                lingRepository, eventBus, runtimeCoordinator, governanceMetricsCollector);
        ContextIsolationFilter resolution = new ContextIsolationFilter();
        GovernanceDecisionFilter governance = new GovernanceDecisionFilter(lingRepository, governanceArbitrator);
        PermissionGovernanceFilter permission = new PermissionGovernanceFilter(permissionService);
        ThreadIsolationGovernanceFilter threadIsolation = new ThreadIsolationGovernanceFilter(lingRepository, governanceMetricsCollector);
        TerminalInvokerFilter terminal = new TerminalInvokerFilter(methodCache, serviceInvoker);

        this.resilienceFilter = resilience;
        this.isolationFilter = threadIsolation;

        builtinFilters.add(new TrafficMetricsFilter(lingRepository, metricsCollector, eventBus));
        builtinFilters.add(stateGuard);
        builtinFilters.add(routing);
        builtinFilters.add(resilience);
        builtinFilters.add(resolution);
        builtinFilters.add(governance);
        builtinFilters.add(permission);
        builtinFilters.add(threadIsolation);
        builtinFilters.add(terminal);

        invalidateCache();
    }

    /**
     * 从灵核类加载器中加载 SPI 扩展过滤器。
     */
    public void loadSpiFilters(ClassLoader lingCoreClassLoader) {
        for (LingInvocationFilter filter : ServiceLoader.load(LingInvocationFilter.class, lingCoreClassLoader)) {
            spiFilters.add(filter);
        }
        invalidateCache();
    }

    /**
     * 允许外部框架动态注入托管过滤器。
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

            List<LingInvocationFilter> all = new ArrayList<>(builtinFilters.size() + spiFilters.size());
            all.addAll(builtinFilters);
            all.addAll(spiFilters);
            all.sort(Comparator.comparingInt(LingInvocationFilter::getOrder));
            validatePhaseContracts(all);
            orderedCache = Collections.unmodifiableList(all);
            return orderedCache;
        }
    }

    private void validatePhaseContracts(List<LingInvocationFilter> filters) {
        // ⚠️ 启动即校验：宁可启动失败，也不要让半失真的治理链带着流量跑起来。
        Map<Class<?>, Integer> orders = new IdentityHashMap<>();
        for (LingInvocationFilter filter : filters) {
            if (isBuiltin(filter)) {
                orders.put(filter.getClass(), filter.getOrder());
            }
        }

        assertOrder(orders, TrafficMetricsFilter.class, FilterPhase.METRICS);
        assertOrder(orders, MacroStateGuardFilter.class, FilterPhase.STATE_GUARD);
        assertOrder(orders, CanaryRoutingFilter.class, FilterPhase.ROUTING);
        assertOrder(orders, ResilienceGovernanceFilter.class, FilterPhase.RESILIENCE);
        assertOrder(orders, ContextIsolationFilter.class, FilterPhase.RESOLUTION);
        assertOrder(orders, GovernanceDecisionFilter.class, FilterPhase.GOVERNANCE);
        assertOrder(orders, PermissionGovernanceFilter.class, FilterPhase.GOVERNANCE + 50);
        assertOrder(orders, ThreadIsolationGovernanceFilter.class, FilterPhase.EXECUTION_ISOLATION);
        assertOrder(orders, TerminalInvokerFilter.class, FilterPhase.TERMINAL);

        assertBefore(orders, TrafficMetricsFilter.class, MacroStateGuardFilter.class);
        assertBefore(orders, MacroStateGuardFilter.class, CanaryRoutingFilter.class);
        assertBefore(orders, CanaryRoutingFilter.class, ResilienceGovernanceFilter.class);
        assertBefore(orders, ResilienceGovernanceFilter.class, ContextIsolationFilter.class);
        assertBefore(orders, ContextIsolationFilter.class, GovernanceDecisionFilter.class);
        assertBefore(orders, GovernanceDecisionFilter.class, PermissionGovernanceFilter.class);
        assertBefore(orders, PermissionGovernanceFilter.class, ThreadIsolationGovernanceFilter.class);
        assertBefore(orders, ThreadIsolationGovernanceFilter.class, TerminalInvokerFilter.class);
    }

    private boolean isBuiltin(LingInvocationFilter filter) {
        return filter instanceof TrafficMetricsFilter
                || filter instanceof MacroStateGuardFilter
                || filter instanceof CanaryRoutingFilter
                || filter instanceof ResilienceGovernanceFilter
                || filter instanceof ContextIsolationFilter
                || filter instanceof GovernanceDecisionFilter
                || filter instanceof PermissionGovernanceFilter
                || filter instanceof ThreadIsolationGovernanceFilter
                || filter instanceof TerminalInvokerFilter;
    }

    private void assertOrder(Map<Class<?>, Integer> orders, Class<?> type, int expectedOrder) {
        Integer actualOrder = orders.get(type);
        if (actualOrder == null) {
            throw new IllegalStateException("Missing builtin filter: " + type.getSimpleName());
        }
        if (actualOrder != expectedOrder) {
            throw new IllegalStateException("Invalid order for " + type.getSimpleName()
                    + ", expected=" + expectedOrder + ", actual=" + actualOrder);
        }
    }

    private void assertBefore(Map<Class<?>, Integer> orders, Class<?> left, Class<?> right) {
        Integer leftOrder = orders.get(left);
        Integer rightOrder = orders.get(right);
        if (leftOrder == null || rightOrder == null) {
            throw new IllegalStateException("Missing builtin filter order for contract validation");
        }
        // 这里校验的是“阶段依赖关系”，不是单纯大小比较。
        if (leftOrder >= rightOrder) {
            throw new IllegalStateException("Phase contract broken: " + left.getSimpleName()
                    + " must run before " + right.getSimpleName());
        }
    }

    private void invalidateCache() {
        this.orderedCache = null;
    }

    /**
     * 驱逐指定灵元的弹性治理组件和隔离线程池。
     */
    public void evictLingResources(String lingId) {
        if (resilienceFilter != null) {
            resilienceFilter.evict(lingId);
        }
        if (isolationFilter != null) {
            isolationFilter.evict(lingId);
        }
    }

    ResilienceGovernanceFilter getResilienceFilter() {
        return resilienceFilter;
    }

    ThreadIsolationGovernanceFilter getIsolationFilter() {
        return isolationFilter;
    }

    public int evictMethodCache(String lingId) {
        if (methodCache == null || lingId == null || lingId.isEmpty()) {
            return 0;
        }
        return methodCache.evictByPrefix(lingId + ":");
    }
}
