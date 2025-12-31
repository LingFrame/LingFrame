package com.lingframe.core.plugin;

import com.lingframe.api.context.PluginContext;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.proxy.SmartServiceProxy;
import com.lingframe.core.spi.PluginServiceInvoker;
import com.lingframe.core.spi.ThreadLocalPropagator;
import com.lingframe.core.spi.TrafficRouter;
import com.lingframe.core.spi.TransactionVerifier;
import jakarta.annotation.Nonnull;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

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

    public PluginRuntime(String pluginId,
                         PluginRuntimeConfig config,
                         ScheduledExecutorService scheduler,
                         ExecutorService executor,
                         GovernanceKernel governanceKernel,
                         EventBus eventBus,
                         TrafficRouter router,
                         PluginServiceInvoker invoker,
                         TransactionVerifier transactionVerifier,
                         List<ThreadLocalPropagator> propagators) {
        this.pluginId = pluginId;
        this.config = config != null ? config : PluginRuntimeConfig.defaults();
        this.router = router;
        this.governanceKernel = governanceKernel;

        // 创建组件
        this.instancePool = new InstancePool(pluginId, this.config.getMaxHistorySnapshots());
        this.serviceRegistry = new ServiceRegistry(pluginId);
        this.invocationExecutor = new InvocationExecutor(
                pluginId,
                executor,
                invoker,
                transactionVerifier,
                propagators,
                this.config
        );
        this.lifecycleManager = new PluginLifecycleManager(
                pluginId,
                instancePool,
                serviceRegistry,
                eventBus,
                scheduler,
                this.config
        );

        log.info("[{}] PluginRuntime initialized", pluginId);
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
    }

    // ==================== 协调逻辑 ====================

    /**
     * 执行服务调用
     */
    public Object invoke(String callerPluginId, String fqsid, Object[] args) throws Exception {
        PluginInstance instance = routeToAvailableInstance(fqsid);

        ServiceRegistry.InvokableService service = serviceRegistry.getService(fqsid);
        if (service == null) {
            throw new NoSuchMethodException("Service not found: " + fqsid);
        }

        return invocationExecutor.execute(instance, service, args, callerPluginId, fqsid);
    }

    /**
     * 路由到可用实例
     */
    public PluginInstance routeToAvailableInstance(String resourceId) {
        InvocationContext ctx = InvocationContext.builder()
                .pluginId(pluginId)
                .resourceId(resourceId)
                .build();

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
        return serviceRegistry.getOrCreateProxy(interfaceClass, k ->
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[]{interfaceClass},
                        new SmartServiceProxy(callerPluginId, this, interfaceClass, governanceKernel)
                ));
    }

    // ==================== 状态查询 ====================

    /**
     * 运行时是否可用
     */
    public boolean isAvailable() {
        return !lifecycleManager.isShutdown() && instancePool.hasAvailableInstance();
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
                lifecycleManager.getStats()
        );
    }

    // ==================== 内部方法 ====================

    private void validateInstance(PluginInstance instance) {
        if (instance == null) {
            throw new IllegalStateException("No available instance: " + pluginId);
        }
        if (instance.isDying()) {
            throw new IllegalStateException("Instance is dying: " + pluginId);
        }
        if (!instance.isReady()) {
            throw new IllegalStateException("Instance not ready: " + pluginId);
        }
        if (!instance.getContainer().isActive()) {
            throw new IllegalStateException("Container inactive: " + pluginId);
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
            PluginLifecycleManager.LifecycleStats lifecycle
    ) {
        @Nonnull
        @Override
        public String toString() {
            return String.format(
                    "RuntimeStats{plugin='%s', available=%s, version='%s', %s, %s, %s, %s}",
                    pluginId, available, version, pool, registry, executor, lifecycle
            );
        }
    }

}