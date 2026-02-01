package com.lingframe.core.plugin;

import com.lingframe.api.context.PluginContext;
import com.lingframe.core.enums.PluginStatus;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.plugin.event.RuntimeEvent;
import com.lingframe.core.plugin.event.RuntimeEventBus;
import com.lingframe.core.proxy.SmartServiceProxy;
import com.lingframe.core.spi.PluginServiceInvoker;
import com.lingframe.core.spi.ThreadLocalPropagator;
import com.lingframe.core.spi.TrafficRouter;
import com.lingframe.core.spi.TransactionVerifier;
import com.lingframe.core.exception.ServiceUnavailableException;
import jakarta.annotation.Nonnull;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 插件运行时
 * <p>
 * 代表一个插件的完整运行环境，协调各子组件工作。
 * <p>
 * 职责：
 * 1. 组件的创建和组装
 * 2. 跨组件的协调逻辑
 * 3. 提供统一的运行时状态查询
 */
@Slf4j
public class PluginRuntime {

    @Getter
    private final String pluginId;

    @Getter
    private final PluginRuntimeConfig config;

    // 内部事件总线
    private final RuntimeEventBus internalEventBus;

    // ===== 核心组件 =====
    @Getter
    private final InstancePool instancePool;

    @Getter
    private final ServiceRegistry serviceRegistry;

    @Getter
    private final InvocationExecutor invocationExecutor;

    @Getter
    private final PluginLifecycleManager lifecycleManager;

    // ===== 协调依赖 =====
    private final TrafficRouter router;
    private final GovernanceKernel governanceKernel;
    private final EventBus monitorBus; // 外部监控总线

    // ===== 状态管理 =====
    @Getter
    private volatile PluginStatus status = PluginStatus.LOADED;

    // ===== 流量统计 =====
    @Getter
    private final AtomicLong totalRequests = new AtomicLong(0);
    @Getter
    private final AtomicLong stableRequests = new AtomicLong(0); // 稳定版命中
    @Getter
    private final AtomicLong canaryRequests = new AtomicLong(0); // 灰度版命中
    @Getter
    private volatile long statsWindowStart = System.currentTimeMillis();

    // ===== 安装时间 =====
    @Getter
    private final long installedAt = System.currentTimeMillis();

    public PluginRuntime(String pluginId,
            PluginRuntimeConfig config,
            ScheduledExecutorService scheduler,
            ExecutorService executor,
            GovernanceKernel governanceKernel,
            EventBus externalEventBus,
            TrafficRouter router,
            PluginServiceInvoker invoker,
            TransactionVerifier transactionVerifier,
            List<ThreadLocalPropagator> propagators) {
        this.pluginId = pluginId;
        this.config = config != null ? config : PluginRuntimeConfig.defaults();
        this.router = router;
        this.governanceKernel = governanceKernel;
        this.monitorBus = externalEventBus; // 保存引用

        // 🔥 创建内部事件总线
        this.internalEventBus = new RuntimeEventBus(pluginId);

        // 创建组件
        this.instancePool = new InstancePool(pluginId, this.config.getMaxHistorySnapshots());
        this.serviceRegistry = new ServiceRegistry(pluginId);
        this.invocationExecutor = new InvocationExecutor(
                pluginId,
                executor,
                invoker,
                transactionVerifier,
                propagators,
                this.config);
        this.lifecycleManager = new PluginLifecycleManager(
                pluginId,
                instancePool,
                internalEventBus, // 内部事件
                externalEventBus, // 外部事件
                scheduler,
                this.config);

        // 🔥 注册组件的事件处理器
        registerEventHandlers();

        // 初始状态设为 LOADED
        this.status = PluginStatus.LOADED;

        log.info("[{}] PluginRuntime initialized", pluginId);
    }

    /**
     * 注册各组件的事件处理器
     */
    private void registerEventHandlers() {
        instancePool.registerEventHandlers(internalEventBus);
        serviceRegistry.registerEventHandlers(internalEventBus);
        invocationExecutor.setEventBus(internalEventBus);
        invocationExecutor.setMonitorBus(monitorBus);

        // 注入外部监控总线
        // externalEventBus is passed in constructor but not stored as field?
        // Constructor: EventBus externalEventBus (L90)
        // L117 passed to lifecycleManager.
        // I need to check if externalEventBus is available here.
        // It is NOT a field of PluginRuntime! L90 arg.
        // L40-83 fields don't show it.
        // But registerEventHandlers is called inside Constructor (L122).
        // BUT registerEventHandlers does NOT take arguments.
        // So it cannot access externalEventBus arg from constructor?
        // Wait, Constructor calls registerEventHandlers() (L122).
        // Inside Constructor, externalEventBus scope is valid?
        // No, registerEventHandlers is a private method.
        // Does it access externalEventBus?
        // NO, it's not a field.
        // So I must add externalEventBus as a field or pass it to
        // registerEventHandlers.

        // Plan: Add externalEventBus field to PluginRuntime.
        // OR: setMonitorBus inside constructor directly.

        // 🔥 可以添加更多监听器，如指标收集
        registerMetricsHandlers();

        log.debug("[{}] Event handlers registered, total subscriptions: {}", pluginId,
                internalEventBus.getSubscriptionCount());

    }

    /**
     * 注册指标收集处理器（示例）
     */
    private void registerMetricsHandlers() {
        // 调用指标
        internalEventBus.subscribe(RuntimeEvent.InvocationCompleted.class, event -> {
            // TODO: 上报到监控系统
            // metricsCollector.recordInvocation(event.fqsid(), event.durationMs(),
            // event.success());
            log.trace("[{}] Invocation completed: {} in {}ms, success={}",
                    pluginId, event.fqsid(), event.durationMs(), event.success());
        });

        // 拒绝指标
        internalEventBus.subscribe(RuntimeEvent.InvocationRejected.class, event -> {
            // TODO: 上报到监控系统
            // metricsCollector.recordRejection(event.fqsid(), event.reason());
            log.warn("[{}] Invocation rejected: {} reason={}",
                    pluginId, event.fqsid(), event.reason());
        });
    }

    // ==================== 状态管理 ====================

    /**
     * 设置插件状态
     */
    public void setStatus(PluginStatus newStatus) {
        PluginStatus oldStatus = this.status;
        this.status = newStatus;
        log.info("[{}] Status changed: {} -> {}", pluginId, oldStatus, newStatus);
    }

    /**
     * 激活插件
     */
    public void activate() {
        if (status == PluginStatus.ACTIVE) {
            log.warn("[{}] Already active", pluginId);
            return;
        }

        if (!instancePool.hasAvailableInstance()) {
            throw new ServiceUnavailableException(pluginId, "No available instance to activate");
        }

        setStatus(PluginStatus.ACTIVE);
    }

    /**
     * 停用插件（保留实例，只是不接收流量）
     */
    public void deactivate() {
        if (status == PluginStatus.LOADED) {
            log.warn("[{}] Already deactivated", pluginId);
            return;
        }
        setStatus(PluginStatus.LOADED);
    }

    // ==================== 流量统计 ====================

    /**
     * 记录请求（在路由后调用）
     */
    public void recordRequest(PluginInstance routedInstance) {
        totalRequests.incrementAndGet();

        PluginInstance defaultInstance = instancePool.getDefault();
        if (routedInstance == defaultInstance) {
            stableRequests.incrementAndGet();
        } else {
            canaryRequests.incrementAndGet();
        }
    }

    /**
     * 重置统计
     */
    public void resetTrafficStats() {
        totalRequests.set(0);
        stableRequests.set(0);
        canaryRequests.set(0);
        statsWindowStart = System.currentTimeMillis();
        log.info("[{}] Traffic stats reset", pluginId);
    }

    // ==================== 版本信息 ====================

    /**
     * 获取所有已部署版本
     */
    public List<String> getAllVersions() {
        return instancePool.getActiveInstances().stream()
                .map(inst -> inst.getDefinition().getVersion())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 获取灰度版本（非默认的第一个版本）
     */
    public String getCanaryVersion() {
        PluginInstance defaultInst = instancePool.getDefault();
        String defaultVersion = defaultInst != null ? defaultInst.getDefinition().getVersion() : null;

        return instancePool.getActiveInstances().stream()
                .map(inst -> inst.getDefinition().getVersion())
                .filter(v -> !Objects.equals(v, defaultVersion))
                .findFirst()
                .orElse(null);
    }

    /**
     * 是否有灰度版本
     */
    public boolean hasCanaryVersion() {
        return getCanaryVersion() != null;
    }

    // ==================== 生命周期（委托）====================

    /**
     * 添加实例
     */
    public void addInstance(PluginInstance instance, PluginContext context, boolean isDefault) {
        lifecycleManager.addInstance(instance, context, isDefault);
    }

    /**
     * 关闭运行时
     */
    public void shutdown() {
        log.info("[{}] Shutting down PluginRuntime", pluginId);
        lifecycleManager.shutdown();

        // 🔥 清理事件总线
        internalEventBus.clear();
    }

    // ==================== 协调逻辑 ====================

    /**
     * 执行服务调用
     */
    /**
     * 执行服务调用 (Legacy)
     */
    public Object invoke(String callerPluginId, String fqsid, Object[] args) throws Exception {
        InvocationContext ctx = InvocationContext.builder()
                .pluginId(pluginId)
                .resourceId(fqsid)
                .args(args)
                .callerPluginId(callerPluginId)
                .build();
        return invoke(ctx);
    }

    /**
     * 执行服务调用 (Context Aware)
     */
    public Object invoke(InvocationContext ctx) throws Exception {
        // 状态检查
        if (status != PluginStatus.ACTIVE) {
            throw new ServiceUnavailableException(pluginId, "Plugin not active");
        }

        String fqsid = ctx.getResourceId();
        PluginInstance instance = routeToAvailableInstance(fqsid, ctx);

        // 🔥 记录流量统计
        recordRequest(instance);

        ServiceRegistry.InvokableService service = serviceRegistry.getService(fqsid);
        if (service == null) {
            throw new NoSuchMethodException("Service not found: " + fqsid);
        }

        return invocationExecutor.executeAsync(instance, service, ctx.getArgs(),
                ctx.getCallerPluginId(), fqsid, ctx.getTimeout());
    }

    /**
     * 路由到可用实例 (Legacy)
     */
    public PluginInstance routeToAvailableInstance(String resourceId) {
        return routeToAvailableInstance(resourceId, null);
    }

    /**
     * 路由到可用实例 (Context Aware)
     */
    public PluginInstance routeToAvailableInstance(String resourceId, InvocationContext ctx) {
        // 如果没有传入 Context，则创建一个临时的 (兼容旧代码)
        if (ctx == null) {
            ctx = InvocationContext.builder()
                    .pluginId(pluginId)
                    .resourceId(resourceId)
                    .build();
        }

        PluginInstance instance = router.route(instancePool.getActiveInstances(), ctx);
        if (instance == null) {
            instance = instancePool.getDefault();
        }

        validateInstance(instance);
        return instance;
    }

    /**
     * 获取服务代理
     */
    public <T> T getServiceProxy(String callerPluginId, Class<T> interfaceClass) {
        return serviceRegistry.getOrCreateProxy(interfaceClass, k -> Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { interfaceClass },
                new SmartServiceProxy(callerPluginId, this, interfaceClass, governanceKernel)));
    }

    // ==================== 状态查询 ====================

    /**
     * 运行时是否可用
     */
    public boolean isAvailable() {
        return status == PluginStatus.ACTIVE &&
                !lifecycleManager.isShutdown() &&
                instancePool.hasAvailableInstance();
    }

    /**
     * 获取当前版本
     */
    public String getVersion() {
        return instancePool.getVersion();
    }

    /**
     * 检查是否有指定类型的 Bean
     */
    public boolean hasBean(Class<?> type) {
        PluginInstance instance = instancePool.getDefault();
        if (instance == null || !instance.getContainer().isActive()) {
            return false;
        }
        try {
            return instance.getContainer().getBean(type) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取运行时统计
     */
    public RuntimeStats getStats() {
        return new RuntimeStats(
                pluginId,
                isAvailable(),
                getVersion(),
                instancePool.getStats(),
                serviceRegistry.getStats(),
                invocationExecutor.getStats(),
                lifecycleManager.getStats());
    }

    // ==================== 内部方法 ====================

    private void validateInstance(PluginInstance instance) {
        if (instance == null) {
            throw new ServiceUnavailableException(pluginId, "No available instance");
        }
        if (instance.isDying()) {
            throw new ServiceUnavailableException(pluginId, "Instance is dying");
        }
        if (!instance.isReady()) {
            throw new ServiceUnavailableException(pluginId, "Instance not ready");
        }
        if (!instance.getContainer().isActive()) {
            throw new ServiceUnavailableException(pluginId, "Container inactive");
        }
    }

    // ==================== 统计信息 ====================

    public record RuntimeStats(
            String pluginId,
            boolean available,
            String version,
            InstancePool.PoolStats pool,
            ServiceRegistry.RegistryStats registry,
            InvocationExecutor.ExecutorStats executor,
            PluginLifecycleManager.LifecycleStats lifecycle) {
        @Nonnull
        @Override
        public String toString() {
            return String.format(
                    "RuntimeStats{plugin='%s', available=%s, version='%s', %s, %s, %s, %s}",
                    pluginId, available, version, pool, registry, executor, lifecycle);
        }
    }

}