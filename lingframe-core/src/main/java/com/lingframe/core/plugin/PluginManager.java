package com.lingframe.core.plugin;

import com.lingframe.api.config.PluginDefinition;
import com.lingframe.api.context.PluginContext;
import com.lingframe.api.event.lifecycle.PluginInstalledEvent;
import com.lingframe.api.event.lifecycle.PluginInstallingEvent;
import com.lingframe.api.event.lifecycle.PluginUninstalledEvent;
import com.lingframe.api.event.lifecycle.PluginUninstallingEvent;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.context.CorePluginContext;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.governance.DefaultTransactionVerifier;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.proxy.GlobalServiceRoutingProxy;
import com.lingframe.core.security.DangerousApiVerifier;
import com.lingframe.core.spi.*;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 插件生命周期管理器
 * <p>
 * 职责：
 * 1. 插件的安装与升级 (Install/Upgrade)
 * 2. 插件的卸载 (Uninstall)
 * 3. 服务的路由与发现 (Service Discovery)
 * 4. 资源的全局管控 (Global Shutdown)
 */
@Slf4j
public class PluginManager {

    // ==================== 常量 ====================
    private static final int QUEUE_CAPACITY = 100;
    private static final long KEEP_ALIVE_TIME = 60L;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 10;

    // ==================== 数据存储 ====================

    /**
     * 插件运行时表：Key=PluginId, Value=Runtime
     */
    private final Map<String, PluginRuntime> runtimes = new ConcurrentHashMap<>();

    /**
     * 协议服务注册表：Key=FQSID, Value=PluginId
     */
    private final Map<String, String> protocolServiceRegistry = new ConcurrentHashMap<>();

    /**
     * 服务缓存：服务类型 -> 提供该服务的插件ID
     */
    private final Map<Class<?>, String> serviceCache = new ConcurrentHashMap<>();

    /**
     * 插件源路径，用于 reload
     */
    private final Map<String, File> pluginSources = new ConcurrentHashMap<>();

    private final Map<String, PluginDefinition> pluginDefinitionMap = new ConcurrentHashMap<>();

    // ==================== 核心依赖 ====================

    private final ContainerFactory containerFactory;
    private final PluginLoaderFactory pluginLoaderFactory;
    private final PermissionService permissionService;
    private final GovernanceKernel governanceKernel;
    private final EventBus eventBus;

    // ==================== 治理组件 ====================

    private final TrafficRouter trafficRouter;
    private final PluginServiceInvoker pluginServiceInvoker;
    private final TransactionVerifier transactionVerifier;

    // ==================== 扩展点 ====================

    private final List<PluginSecurityVerifier> verifiers;
    private final List<ThreadLocalPropagator> propagators;

    // ==================== 基础设施 ====================

    private final LingFrameConfig lingFrameConfig;
    private final LocalGovernanceRegistry localGovernanceRegistry;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService pluginExecutor;
    private final AtomicInteger threadNumber = new AtomicInteger(1);

    public PluginManager(ContainerFactory containerFactory,
                         PermissionService permissionService,
                         GovernanceKernel governanceKernel,
                         PluginLoaderFactory pluginLoaderFactory,
                         List<PluginSecurityVerifier> verifiers,
                         EventBus eventBus,
                         TrafficRouter trafficRouter,
                         PluginServiceInvoker pluginServiceInvoker,
                         TransactionVerifier transactionVerifier,
                         List<ThreadLocalPropagator> propagators,
                         LingFrameConfig lingFrameConfig,
                         LocalGovernanceRegistry localGovernanceRegistry) {
        // 核心依赖
        this.containerFactory = containerFactory;
        this.pluginLoaderFactory = pluginLoaderFactory;
        this.permissionService = permissionService;
        this.governanceKernel = governanceKernel;
        this.eventBus = eventBus;

        // 治理组件
        this.trafficRouter = trafficRouter;
        this.pluginServiceInvoker = pluginServiceInvoker;
        this.transactionVerifier = transactionVerifier != null
                ? transactionVerifier
                : new DefaultTransactionVerifier();

        // 扩展点（防御性处理）
        this.verifiers = new ArrayList<>();
        if (verifiers != null) {
            this.verifiers.addAll(verifiers);
        }
        boolean hasBytecodeVerifier = this.verifiers.stream()
                .anyMatch(v -> v instanceof DangerousApiVerifier);
        if (!hasBytecodeVerifier) {
            // 防御性处理：如果没有字节码验证器，添加默认的
            log.info("No DangerousApiVerifier found in verifiers, adding default DangerousApiVerifier");
            this.verifiers.add(new DangerousApiVerifier());
        }
        this.propagators = propagators != null ? propagators : Collections.emptyList();

        // 配置
        this.lingFrameConfig = lingFrameConfig;
        this.localGovernanceRegistry = localGovernanceRegistry;

        // 基础设施
        this.scheduler = createScheduler();
        this.pluginExecutor = createExecutor();
    }

    // ==================== 安装 API ====================

    /**
     * 安装 Jar 包插件 (生产模式)
     */
    public void install(PluginDefinition pluginDefinition, File jarFile) {
        // 验证
        pluginDefinition.validate();

        String pluginId = pluginDefinition.getId();
        log.info("Installing plugin: {} v{}", pluginId, pluginDefinition.getVersion());

        pluginSources.put(pluginId, jarFile);
        pluginDefinitionMap.put(pluginId, pluginDefinition);
        doInstall(pluginDefinition, jarFile, true, Collections.emptyMap());
    }

    /**
     * 安装目录插件 (开发模式)
     */
    public void installDev(PluginDefinition pluginDefinition, File classesDir) {
        // 验证
        pluginDefinition.validate();

        if (!classesDir.exists() || !classesDir.isDirectory()) {
            throw new IllegalArgumentException("Invalid classes directory: " + classesDir);
        }

        String pluginId = pluginDefinition.getId();

        log.info("Installing plugin in DEV mode: {} (Dir: {})", pluginId, classesDir.getName());
        pluginSources.put(pluginId, classesDir);
        pluginDefinitionMap.put(pluginId, pluginDefinition);
        doInstall(pluginDefinition, classesDir, true, Collections.emptyMap());
    }

    /**
     * 金丝雀/灰度发布入口
     *
     * @param labels 实例的固有标签
     */
    public void deployCanary(PluginDefinition pluginDefinition, File source, Map<String, String> labels) {
        // 验证
        pluginDefinition.validate();

        String pluginId = pluginDefinition.getId();

        log.info("Deploying canary plugin: {} v{}", pluginId, pluginDefinition.getVersion());
        pluginSources.put(pluginId, source);
        pluginDefinitionMap.put(pluginId, pluginDefinition);
        doInstall(pluginDefinition, source, false, labels);
    }

    /**
     * 重载插件 (热替换)
     */
    public void reload(String pluginId) {
        File source = pluginSources.get(pluginId);
        if (source == null) {
            log.warn("Cannot reload plugin {}: source not found", pluginId);
            return;
        }
        PluginDefinition pluginDefinition = pluginDefinitionMap.get(pluginId);
        if (pluginDefinition == null) {
            log.warn("Cannot reload plugin {}: pluginDefinition not found", pluginId);
            return;
        }
        log.info("Reloading plugin: {}", pluginId);

        // 获取旧标签
        Map<String, String> oldLabels = getDefaultInstanceLabels(pluginId);

        // ✅ 创建副本再修改，不影响原对象
        PluginDefinition reloadDef = pluginDefinition.copy();
        reloadDef.setVersion("dev-reload-" + System.currentTimeMillis());
        doInstall(reloadDef, source, true, oldLabels);
    }

    /**
     * 卸载插件
     * <p>
     * 逻辑：将当前活跃实例标记为濒死，从管理列表中移除，等待引用计数归零后自然销毁
     */
    public void uninstall(String pluginId) {
        log.info("Uninstalling plugin: {}", pluginId);

        // Hook 1: Pre-Uninstall (可被拦截，例如防止误删核心插件)
        eventBus.publish(new PluginUninstallingEvent(pluginId));

        PluginRuntime runtime = runtimes.remove(pluginId);
        if (runtime == null) {
            log.warn("Plugin not found: {}", pluginId);
            return;
        }

        // 清理各种状态
        serviceCache.entrySet().removeIf(e -> e.getValue().equals(pluginId));
        pluginSources.remove(pluginId);
        pluginDefinitionMap.remove(pluginId);

        runtime.shutdown();
        unregisterProtocolServices(pluginId);
        eventBus.unsubscribeAll(pluginId);
        permissionService.removePlugin(pluginId);

        // Hook 2: Post-Uninstall (清理配置、删除临时文件)
        eventBus.publish(new PluginUninstalledEvent(pluginId));
    }

    // ==================== 服务发现 API ====================

    /**
     * 获取插件对外暴露的服务 (动态代理)
     *
     * @param callerPluginId 调用方插件 ID
     * @param serviceType    服务接口类型
     * @return 服务代理对象
     */
    public <T> T getService(String callerPluginId, Class<T> serviceType) {
        // 查缓存
        String cachedPluginId = serviceCache.get(serviceType);
        if (cachedPluginId != null) {
            PluginRuntime runtime = runtimes.get(cachedPluginId);
            if (runtime != null && runtime.hasBean(serviceType)) {
                try {
                    return runtime.getServiceProxy(callerPluginId, serviceType);
                } catch (Exception e) {
                    log.debug("Cached service failed, will re-discover: {}", e.getMessage());
                }
            }
            serviceCache.remove(serviceType);
        }

        // 遍历查找，发现多个实现时，记录下来
        List<String> candidates = new ArrayList<>();
        for (PluginRuntime runtime : runtimes.values()) {
            if (runtime.hasBean(serviceType))
                candidates.add(runtime.getPluginId());
        }

        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Service not found: " + serviceType.getName());
        }

        if (candidates.size() > 1) {
            Collections.sort(candidates);
            log.warn("Multiple implementations found for {}: {}. Using {}",
                    serviceType.getSimpleName(), candidates, candidates.getFirst());
        }

        // 获取服务（单个或多个取第一个）
        String targetPluginId = candidates.getFirst();
        try {
            T proxy = runtimes.get(targetPluginId).getServiceProxy(callerPluginId, serviceType);
            serviceCache.put(serviceType, targetPluginId);
            log.debug("Service {} resolved to plugin {}", serviceType.getSimpleName(), targetPluginId);
            return proxy;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to get service " + serviceType.getName() + " from " + targetPluginId, e);
        }
    }

    /**
     * 获取服务的全局路由代理 (宿主专用)
     * <p>
     * 解决"鸡生蛋"问题：在插件还未启动时就能创建出代理对象
     */
    @SuppressWarnings("unchecked")
    public <T> T getGlobalServiceProxy(String callerPluginId, Class<T> serviceType, String targetPluginId) {
        return (T) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{serviceType},
                new GlobalServiceRoutingProxy(callerPluginId, serviceType, targetPluginId, this, governanceKernel));
    }

    // ==================== 协议服务 API ====================

    /**
     * 处理协议调用 (由 CorePluginContext.invoke 调用)
     *
     * @param callerPluginId 调用方插件ID (用于审计)
     * @param fqsid          全路径服务ID (Plugin ID:Short ID)
     * @param args           参数列表
     * @return 方法执行结果
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> invokeService(String callerPluginId, String fqsid, Object... args) {
        String targetPluginId = protocolServiceRegistry.get(fqsid);
        if (targetPluginId == null) {
            log.warn("Service not found in registry: {}", fqsid);
            return Optional.empty();
        }

        PluginRuntime runtime = runtimes.get(targetPluginId);
        if (runtime == null) {
            log.warn("Target plugin runtime not found: {}", targetPluginId);
            return Optional.empty();
        }

        ServiceRegistry.InvokableService invokable = runtime.getServiceRegistry().getService(fqsid);
        if (invokable == null) {
            log.warn("Method not registered in runtime: {}", fqsid);
            return Optional.empty();
        }

        InvocationContext ctx = InvocationContext.builder()
                .callerPluginId(callerPluginId)
                .pluginId(targetPluginId)
                .resourceType("RPC_HOST_INVOKE")
                .resourceId(fqsid)
                .operation(invokable.method().getName())
                .args(args)
                .requiredPermission(fqsid)
                .accessType(AccessType.EXECUTE)
                .shouldAudit(true)
                .auditAction("HostInvoke:" + fqsid)
                .labels(Collections.emptyMap())
                .build();

        try {
            Object result = governanceKernel.invoke(runtime, invokable.method(), ctx, () -> {
                try {
                    return runtime.invoke(callerPluginId, fqsid, args);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            return Optional.ofNullable((T) result);
        } catch (Exception e) {
            log.error("Invoke failed", e);
            throw new RuntimeException("Protocol service invoke failed", e);
        }
    }

    /**
     * 注册协议服务 (供 Runtime 层调用)
     */
    public void registerProtocolService(String pluginId, String fqsid, Object bean, Method method) {
        // 冲突检测
        String existing = protocolServiceRegistry.get(fqsid);
        if (existing != null && !existing.equals(pluginId)) {
            log.warn("FQSID Conflict! [{}] owned by [{}] is being overwritten by [{}]",
                    fqsid, existing, pluginId);
        }

        // 注册到路由表
        protocolServiceRegistry.put(fqsid, pluginId);

        // 注册到 Runtime 的执行缓存
        PluginRuntime runtime = runtimes.get(pluginId);
        if (runtime != null) {
            runtime.getServiceRegistry().registerService(fqsid, bean, method);
        }

        log.info("[{}] Registered Service: {}", pluginId, fqsid);
    }

    // ==================== 查询 API ====================

    public Set<String> getInstalledPlugins() {
        return Collections.unmodifiableSet(runtimes.keySet());
    }

    public String getPluginVersion(String pluginId) {
        PluginRuntime runtime = runtimes.get(pluginId);
        return runtime != null ? runtime.getVersion() : null;
    }

    public PluginRuntime getRuntime(String pluginId) {
        return runtimes.get(pluginId);
    }

    public boolean hasBean(String pluginId, Class<?> beanType) {
        PluginRuntime runtime = runtimes.get(pluginId);
        return runtime != null && runtime.hasBean(beanType);
    }

    // ==================== 生命周期 ====================

    /**
     * 全局关闭
     * <p>
     * 应用退出时调用，强制销毁所有资源
     */
    public void shutdown() {
        log.info("Shutting down PluginManager...");

        // 停止调度器
        shutdownExecutor(scheduler);

        // 关闭所有运行时
        for (PluginRuntime runtime : runtimes.values()) {
            try {
                runtime.shutdown();
            } catch (Exception e) {
                log.error("Error shutting down plugin: {}", runtime.getPluginId(), e);
            }
        }

        // 清理状态
        runtimes.clear();
        serviceCache.clear();
        protocolServiceRegistry.clear();
        pluginSources.clear();

        // 关闭线程池
        shutdownExecutor(pluginExecutor);

        log.info("PluginManager shutdown complete.");
    }

    // ==================== 内部方法 ====================

    private Map<String, String> getDefaultInstanceLabels(String pluginId) {
        PluginRuntime runtime = runtimes.get(pluginId);
        if (runtime == null) {
            return Collections.emptyMap();
        }
        PluginInstance defaultInstance = runtime.getInstancePool().getDefault();
        if (defaultInstance == null) {
            return Collections.emptyMap();
        }
        return new HashMap<>(defaultInstance.getLabels());
    }

    /**
     * 安装或升级插件 (核心入口)
     * <p>
     * 支持热替换：如果插件已存在，则触发蓝绿部署流程
     */
    private void doInstall(PluginDefinition pluginDefinition, File sourceFile,
                           boolean isDefault, Map<String, String> labels) {
        String pluginId = pluginDefinition.getId();
        String version = pluginDefinition.getVersion();
        eventBus.publish(new PluginInstallingEvent(pluginId, version, sourceFile));

        ClassLoader pluginClassLoader = null;
        PluginContainer container = null;
        boolean isNewRuntime = false; // ✅ 标记是否新创建
        try {
            // 安全验证
            for (PluginSecurityVerifier verifier : verifiers) {
                verifier.verify(pluginId, sourceFile);
            }

            // 热更新时清理缓存
            if (runtimes.containsKey(pluginId)) {
                serviceCache.entrySet().removeIf(e -> e.getValue().equals(pluginId));
                log.info("[{}] Preparing for upgrade", pluginId);
            } else {
                isNewRuntime = true; // ✅ 标记为新创建
            }

            // 创建隔离环境
            pluginClassLoader = pluginLoaderFactory.create(pluginId, sourceFile, getClass().getClassLoader());
            container = containerFactory.create(pluginId, sourceFile, pluginClassLoader);

            // 创建实例
            // ✅ 每个实例持有独立副本
            PluginDefinition instanceDef = pluginDefinition.copy();
            PluginInstance instance = new PluginInstance(container, instanceDef);
            instance.addLabels(labels);

            // 获取或创建运行时
            PluginRuntime runtime = runtimes.computeIfAbsent(pluginId, this::createRuntime);

            // 创建上下文并添加实例
            PluginContext context = new CorePluginContext(pluginId, this, permissionService, eventBus);
            runtime.addInstance(instance, context, isDefault);

            eventBus.publish(new PluginInstalledEvent(pluginId, version));
            log.info("[{}] Installed successfully", pluginId);

        } catch (Throwable t) {
            log.error("Failed to install plugin: {} v{}", pluginId, version, t);

            // ✅ 清理失败创建的 Runtime
            if (isNewRuntime) {
                PluginRuntime failedRuntime = runtimes.remove(pluginId);
                if (failedRuntime != null) {
                    try {
                        failedRuntime.shutdown();
                    } catch (Exception e) {
                        log.warn("Failed to cleanup runtime for {}", pluginId, e);
                    }
                }
                // 清理存储
                pluginSources.remove(pluginId);
                pluginDefinitionMap.remove(pluginId);
            }

            cleanupOnFailure(pluginClassLoader, container);
            throw t;
        }
    }

    private PluginRuntime createRuntime(String pluginId) {
        return new PluginRuntime(
                pluginId, lingFrameConfig.getRuntimeConfig(),
                scheduler, pluginExecutor,
                governanceKernel, eventBus, trafficRouter,
                pluginServiceInvoker, transactionVerifier, propagators);
    }

    private void cleanupOnFailure(ClassLoader classLoader, PluginContainer container) {
        if (container != null) {
            try {
                container.stop();
            } catch (Exception e) {
                log.warn("Failed to stop container", e);
            }
        }
        if (classLoader instanceof AutoCloseable) {
            try {
                ((AutoCloseable) classLoader).close();
            } catch (Exception e) {
                log.warn("Failed to close classloader", e);
            }
        }
    }

    private void unregisterProtocolServices(String pluginId) {
        protocolServiceRegistry.entrySet().removeIf(entry -> {
            if (entry.getValue().equals(pluginId)) {
                log.info("[{}] Unregistered FQSID: {}", pluginId, entry.getKey());
                return true;
            }
            return false;
        });
    }

    // ==================== 基础设施创建 ====================

    private ScheduledExecutorService createScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lingframe-plugin-cleaner");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler(
                    (thread, e) -> log.error("Scheduler thread {} error: {}", thread.getName(), e.getMessage()));
            return t;
        });
    }

    private ExecutorService createExecutor() {
        return new ThreadPoolExecutor(
                lingFrameConfig.getCorePoolSize(),
                lingFrameConfig.getCorePoolSize() * 2,
                KEEP_ALIVE_TIME, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "plugin-executor-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    t.setUncaughtExceptionHandler(
                            (thread, e) -> log.error("Executor thread {} error: {}", thread.getName(), e.getMessage()));
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Executor did not terminate gracefully, forcing...");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}