package com.lingframe.core.ling;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.event.lifecycle.LingInstalledEvent;
import com.lingframe.api.event.lifecycle.LingInstallingEvent;
import com.lingframe.api.event.lifecycle.LingUninstalledEvent;
import com.lingframe.api.event.lifecycle.LingUninstallingEvent;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.context.CoreLingContext;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.governance.DefaultTransactionVerifier;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.proxy.GlobalServiceRoutingProxy;
import com.lingframe.core.resource.BasicResourceGuard;
import com.lingframe.core.security.DangerousApiVerifier;
import com.lingframe.core.spi.*;
import com.lingframe.core.exception.ServiceNotFoundException;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.core.exception.InvocationException;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单元生命周期管理器
 * <p>
 * 职责：
 * 1. 单元的安装与升级 (Install/Upgrade)
 * 2. 单元的卸载 (Uninstall)
 * 3. 服务的路由与发现 (Service Discovery)
 * 4. 资源的全局管控 (Global Shutdown)
 */
@Slf4j
public class LingManager {

    // ==================== 常量 ====================
    private static final int QUEUE_CAPACITY = 100;
    private static final long KEEP_ALIVE_TIME = 60L;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 10;

    // ==================== 数据存储 ====================

    /**
     * 单元运行时表：Key=LingId, Value=Runtime
     */
    private final Map<String, LingRuntime> runtimes = new ConcurrentHashMap<>();

    /**
     * 协议服务注册表：Key=FQSID, Value=LingId
     */
    private final Map<String, String> protocolServiceRegistry = new ConcurrentHashMap<>();

    /**
     * 服务缓存：服务类型 -> 提供该服务的单元ID
     */
    private final Map<Class<?>, String> serviceCache = new ConcurrentHashMap<>();

    /**
     * 单元源路径，用于 reload
     */
    private final Map<String, File> lingSources = new ConcurrentHashMap<>();

    private final Map<String, LingDefinition> lingDefinitionMap = new ConcurrentHashMap<>();

    // ==================== 核心依赖 ====================

    private final ContainerFactory containerFactory;
    private final LingLoaderFactory lingLoaderFactory;
    private final PermissionService permissionService;
    private final GovernanceKernel governanceKernel;
    private final EventBus eventBus;

    // ==================== 治理组件 ====================

    private final TrafficRouter trafficRouter;
    private final LingServiceInvoker lingServiceInvoker;
    private final TransactionVerifier transactionVerifier;

    // ==================== 扩展点 ====================

    private final List<LingSecurityVerifier> verifiers;
    private final List<ThreadLocalPropagator> propagators;

    // ==================== 资源管理 ====================

    private final ResourceGuard resourceGuard;

    // ==================== 基础设施 ====================

    private final LingFrameConfig lingFrameConfig;
    private final LocalGovernanceRegistry localGovernanceRegistry;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger threadNumber = new AtomicInteger(1);

    // ==================== 线程池预算管理 ====================

    /**
     * 全局线程预算剩余配额
     */
    private final AtomicInteger globalThreadBudget;

    /**
     * 每个单元实际分配的线程数：Key=LingId, Value=分配线程数
     * 用于卸载时归还预算
     */
    private final Map<String, Integer> lingThreadAllocations = new ConcurrentHashMap<>();

    public LingManager(ContainerFactory containerFactory,
            PermissionService permissionService,
            GovernanceKernel governanceKernel,
            LingLoaderFactory lingLoaderFactory,
            List<LingSecurityVerifier> verifiers,
            EventBus eventBus,
            TrafficRouter trafficRouter,
            LingServiceInvoker lingServiceInvoker,
            TransactionVerifier transactionVerifier,
            List<ThreadLocalPropagator> propagators,
            LingFrameConfig lingFrameConfig,
            LocalGovernanceRegistry localGovernanceRegistry,
            ResourceGuard resourceGuard) {
        // 核心依赖
        this.containerFactory = containerFactory;
        this.lingLoaderFactory = lingLoaderFactory;
        this.permissionService = permissionService;
        this.governanceKernel = governanceKernel;
        this.eventBus = eventBus;

        // 治理组件
        this.trafficRouter = trafficRouter;
        this.lingServiceInvoker = lingServiceInvoker;
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

        // 资源管理（防御性处理：如未注入则使用默认实现）
        this.resourceGuard = resourceGuard != null ? resourceGuard : new BasicResourceGuard();

        // 基础设施
        this.scheduler = createScheduler();
        this.globalThreadBudget = new AtomicInteger(lingFrameConfig.getGlobalMaxLingThreads());
    }

    // ==================== 安装 API ====================

    /**
     * 安装 Jar 包单元 (生产模式)
     */
    public void install(LingDefinition lingDefinition, File jarFile) {
        // 验证
        lingDefinition.validate();

        String lingId = lingDefinition.getId();
        log.info("Installing ling: {} v{}", lingId, lingDefinition.getVersion());

        lingSources.put(lingId, jarFile);
        lingDefinitionMap.put(lingId, lingDefinition);
        doInstall(lingDefinition, jarFile, true, Collections.emptyMap());
    }

    /**
     * 安装目录单元 (开发模式)
     */
    public void installDev(LingDefinition lingDefinition, File classesDir) {
        // 验证
        lingDefinition.validate();

        if (!classesDir.exists() || !classesDir.isDirectory()) {
            throw new InvalidArgumentException("classesDir", "Invalid classes directory: " + classesDir);
        }

        String lingId = lingDefinition.getId();

        log.info("Installing ling in DEV mode: {} (Dir: {})", lingId, classesDir.getName());
        lingSources.put(lingId, classesDir);
        lingDefinitionMap.put(lingId, lingDefinition);
        doInstall(lingDefinition, classesDir, true, Collections.emptyMap());
    }

    /**
     * 金丝雀/灰度发布入口
     *
     * @param labels 实例的固有标签
     */
    public void deployCanary(LingDefinition lingDefinition, File source, Map<String, String> labels) {
        // 验证
        lingDefinition.validate();

        String lingId = lingDefinition.getId();

        log.info("Deploying canary ling: {} v{}", lingId, lingDefinition.getVersion());
        lingSources.put(lingId, source);
        lingDefinitionMap.put(lingId, lingDefinition);
        doInstall(lingDefinition, source, false, labels);
    }

    /**
     * 重载单元 (热替换)
     */
    public void reload(String lingId) {
        File source = lingSources.get(lingId);
        if (source == null) {
            log.warn("Cannot reload ling {}: source not found", lingId);
            return;
        }
        LingDefinition lingDefinition = lingDefinitionMap.get(lingId);
        if (lingDefinition == null) {
            log.warn("Cannot reload ling {}: lingDefinition not found", lingId);
            return;
        }
        log.info("Reloading ling: {}", lingId);

        // 获取旧标签
        Map<String, String> oldLabels = getDefaultInstanceLabels(lingId);

        // ✅ 创建副本再修改，不影响原对象
        LingDefinition reloadDef = lingDefinition.copy();
        reloadDef.setVersion("dev-reload-" + System.currentTimeMillis());
        doInstall(reloadDef, source, true, oldLabels);
    }

    /**
     * 卸载单元
     * <p>
     * 逻辑：将当前活跃实例标记为濒死，从管理列表中移除，等待引用计数归零后自然销毁
     */
    public void uninstall(String lingId) {
        log.info("Uninstalling ling: {}", lingId);

        // Hook 1: Pre-Uninstall (可被拦截，例如防止误删核心单元)
        eventBus.publish(new LingUninstallingEvent(lingId));

        LingRuntime runtime = runtimes.remove(lingId);
        if (runtime == null) {
            log.warn("Ling not found: {}", lingId);
            return;
        }

        // 🔥 关键：在 shutdown 之前获取 ClassLoader 引用
        // 因为 shutdown 后 container 会将 classLoader 置 null
        ClassLoader lingClassLoader = null;
        LingInstance defaultInst = runtime.getInstancePool().getDefault();
        if (defaultInst != null && defaultInst.getContainer() != null) {
            lingClassLoader = defaultInst.getContainer().getClassLoader();
        }

        // 清理各种状态
        serviceCache.entrySet().removeIf(e -> e.getValue().equals(lingId));
        // 🔥 额外清理：移除由该单元 ClassLoader 加载的 Class Key，防止 Class → ClassLoader 引用链残留
        if (lingClassLoader != null) {
            final ClassLoader cl = lingClassLoader;
            serviceCache.entrySet().removeIf(e -> e.getKey().getClassLoader() == cl);
        }
        lingSources.remove(lingId);
        lingDefinitionMap.remove(lingId);

        try {
            runtime.shutdown();
        } catch (Exception e) {
            log.warn("Error shutting down runtime for ling: {}", lingId, e);
        }
        // 归还线程预算
        reclaimThreadBudget(lingId);

        unregisterProtocolServices(lingId);
        eventBus.unsubscribeAll(lingId);
        permissionService.removeLing(lingId);

        // 资源清理和泄漏检测现在由 LingLifecycleManager.destroyInstance 触发

        // Hook 2: Post-Uninstall (清理配置、删除临时文件)
        eventBus.publish(new LingUninstalledEvent(lingId));
    }

    // ==================== 服务发现 API ====================

    /**
     * 获取单元对外暴露的服务 (动态代理)
     *
     * @param callerLingId 调用方单元 ID
     * @param serviceType    服务接口类型
     * @return 服务代理对象
     */
    public <T> T getService(String callerLingId, Class<T> serviceType) {
        // 查缓存
        String cachedLingId = serviceCache.get(serviceType);
        if (cachedLingId != null) {
            LingRuntime runtime = runtimes.get(cachedLingId);
            if (runtime != null && runtime.hasBean(serviceType)) {
                try {
                    return runtime.getServiceProxy(callerLingId, serviceType);
                } catch (Exception e) {
                    log.debug("Cached service failed, will re-discover: {}", e.getMessage());
                }
            }
            serviceCache.remove(serviceType);
        }

        // 遍历查找，发现多个实现时，记录下来
        List<String> candidates = new ArrayList<>();
        for (LingRuntime runtime : runtimes.values()) {
            if (runtime.hasBean(serviceType))
                candidates.add(runtime.getLingId());
        }

        if (candidates.isEmpty()) {
            throw new ServiceNotFoundException(serviceType.getName());
        }

        if (candidates.size() > 1) {
            Collections.sort(candidates);
            log.warn("Multiple implementations found for {}: {}. Using {}",
                    serviceType.getSimpleName(), candidates, candidates.get(0));
        }

        // 获取服务（单个或多个取第一个）
        String targetLingId = candidates.get(0);
        try {
            T proxy = runtimes.get(targetLingId).getServiceProxy(callerLingId, serviceType);
            serviceCache.put(serviceType, targetLingId);
            log.debug("Service {} resolved to ling {}", serviceType.getSimpleName(), targetLingId);
            return proxy;
        } catch (Exception e) {
            throw new ServiceNotFoundException(serviceType.getName(), targetLingId);
        }
    }

    /**
     * 获取服务的全局路由代理 (灵核专用)
     * <p>
     * 解决"鸡生蛋"问题：在单元还未启动时就能创建出代理对象
     */
    @SuppressWarnings("unchecked")
    public <T> T getGlobalServiceProxy(String callerLingId, Class<T> serviceType, String targetLingId) {
        return (T) Proxy.newProxyInstance(
                // 🔥🔥🔥 关键修复：使用接口所在的 ClassLoader 🔥🔥🔥
                serviceType.getClassLoader(),
                new Class[] { serviceType },
                new GlobalServiceRoutingProxy(callerLingId, serviceType, targetLingId, this, governanceKernel));
    }

    // ==================== 协议服务 API ====================

    /**
     * 处理协议调用 (由 CoreLingContext.invoke 调用)
     *
     * @param callerLingId 调用方单元ID (用于审计)
     * @param fqsid          全路径服务ID (ling ID:Short ID)
     * @param args           参数列表
     * @return 方法执行结果
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> invokeService(String callerLingId, String fqsid, Object... args) {
        String targetLingId = protocolServiceRegistry.get(fqsid);
        if (targetLingId == null) {
            log.warn("Service not found in registry: {}", fqsid);
            return Optional.empty();
        }

        LingRuntime runtime = runtimes.get(targetLingId);
        if (runtime == null) {
            log.warn("Target ling runtime not found: {}", targetLingId);
            return Optional.empty();
        }

        ServiceRegistry.InvokableService invokable = runtime.getServiceRegistry().getService(fqsid);
        if (invokable == null) {
            log.warn("Method not registered in runtime: {}", fqsid);
            return Optional.empty();
        }

        InvocationContext ctx = InvocationContext.builder()
                .callerLingId(callerLingId)
                .lingId(targetLingId)
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
                    return runtime.invoke(callerLingId, fqsid, args);
                } catch (Exception e) {
                    throw new InvocationException("Invocation failed", e);
                }
            });
            return Optional.ofNullable((T) result);
        } catch (Exception e) {
            log.error("Invoke failed", e);
            throw new InvocationException("Protocol service invoke failed", e);
        }
    }

    /**
     * 注册协议服务 (供 Runtime 层调用)
     */
    public void registerProtocolService(String lingId, String fqsid, Object bean, Method method) {
        // 冲突检测
        String existing = protocolServiceRegistry.get(fqsid);
        if (existing != null && !existing.equals(lingId)) {
            log.warn("FQSID Conflict! [{}] owned by [{}] is being overwritten by [{}]",
                    fqsid, existing, lingId);
        }

        // 注册到路由表
        protocolServiceRegistry.put(fqsid, lingId);

        // 注册到 Runtime 的执行缓存
        LingRuntime runtime = runtimes.get(lingId);
        if (runtime != null) {
            runtime.getServiceRegistry().registerService(fqsid, bean, method);
        }

        log.info("[{}] Registered Service: {}", lingId, fqsid);
    }

    // ==================== 查询 API ====================

    public Set<String> getInstalledLings() {
        return Collections.unmodifiableSet(runtimes.keySet());
    }

    public String getLingVersion(String lingId) {
        LingRuntime runtime = runtimes.get(lingId);
        return runtime != null ? runtime.getVersion() : null;
    }

    public LingRuntime getRuntime(String lingId) {
        return runtimes.get(lingId);
    }

    public boolean hasBean(String lingId, Class<?> beanType) {
        LingRuntime runtime = runtimes.get(lingId);
        return runtime != null && runtime.hasBean(beanType);
    }

    // ==================== 生命周期 ====================

    /**
     * 全局关闭
     * <p>
     * 应用退出时调用，强制销毁所有资源
     */
    public void shutdown() {
        log.info("Shutting down LingManager...");

        // 停止调度器 (使用 shutdownNow 取消延迟任务)
        shutdownExecutorNow(scheduler);

        // 关闭所有运行时
        for (LingRuntime runtime : runtimes.values()) {
            try {
                runtime.shutdown();
            } catch (Exception e) {
                log.error("Error shutting down ling: {}", runtime.getLingId(), e);
            }
        }

        // 关闭资源守卫
        if (resourceGuard != null) {
            try {
                resourceGuard.shutdown();
            } catch (Exception e) {
                log.error("Error shutting down ResourceGuard", e);
            }
        }

        // 清理状态
        runtimes.clear();
        serviceCache.clear();
        protocolServiceRegistry.clear();
        lingSources.clear();

        // 归还所有线程预算 (各单元线程池已由 runtime.shutdown() 关闭)
        lingThreadAllocations.clear();
        globalThreadBudget.set(lingFrameConfig.getGlobalMaxLingThreads());

        log.info("LingManager shutdown complete.");
    }

    // ==================== 内部方法 ====================

    private Map<String, String> getDefaultInstanceLabels(String lingId) {
        LingRuntime runtime = runtimes.get(lingId);
        if (runtime == null) {
            return Collections.emptyMap();
        }
        LingInstance defaultInstance = runtime.getInstancePool().getDefault();
        if (defaultInstance == null) {
            return Collections.emptyMap();
        }
        return new HashMap<>(defaultInstance.getLabels());
    }

    /**
     * 安装或升级单元 (核心入口)
     * <p>
     * 支持热替换：如果单元已存在，则触发蓝绿部署流程
     */
    private void doInstall(LingDefinition lingDefinition, File sourceFile,
            boolean isDefault, Map<String, String> labels) {
        String lingId = lingDefinition.getId();
        String version = lingDefinition.getVersion();
        eventBus.publish(new LingInstallingEvent(lingId, version, sourceFile));

        ClassLoader lingClassLoader = null;
        LingContainer container = null;
        boolean isNewRuntime = false; // ✅ 标记是否新创建
        try {
            // 安全验证
            for (LingSecurityVerifier verifier : verifiers) {
                verifier.verify(lingId, sourceFile);
            }

            // 热更新时清理缓存
            if (runtimes.containsKey(lingId)) {
                serviceCache.entrySet().removeIf(e -> e.getValue().equals(lingId));
                log.info("[{}] Preparing for upgrade", lingId);
            } else {
                isNewRuntime = true; // ✅ 标记为新创建
            }

            // 创建隔离环境
            lingClassLoader = lingLoaderFactory.create(lingId, sourceFile, getClass().getClassLoader());
            container = containerFactory.create(lingId, sourceFile, lingClassLoader);

            // 创建实例
            // ✅ 每个实例持有独立副本
            LingDefinition instanceDef = lingDefinition.copy();
            LingInstance instance = new LingInstance(container, instanceDef);
            instance.addLabels(labels);

            // 获取或创建运行时
            LingRuntime runtime = runtimes.computeIfAbsent(lingId, this::createRuntime);

            // 创建上下文并添加实例
            LingContext context = new CoreLingContext(lingId, this, permissionService, eventBus);
            runtime.addInstance(instance, context, isDefault);

            // ✅ 初始化权限 (从配置加载)
            if (lingDefinition.getGovernance() != null
                    && lingDefinition.getGovernance().getCapabilities() != null) {
                for (GovernancePolicy.CapabilityRule rule : lingDefinition.getGovernance()
                        .getCapabilities()) {
                    try {
                        AccessType accessType = AccessType.valueOf(rule.getAccessType().toUpperCase());
                        permissionService.grant(lingId, rule.getCapability(), accessType);
                        log.debug("[{}] Granted permission: {} -> {}", lingId, rule.getCapability(), accessType);
                    } catch (IllegalArgumentException e) {
                        log.warn("[{}] Invalid access type in permission config: {}", lingId, rule.getAccessType());
                    }
                }
            }

            eventBus.publish(new LingInstalledEvent(lingId, version));
            log.info("[{}] Installed successfully", lingId);

        } catch (Throwable t) {
            log.error("Failed to install ling: {} v{}", lingId, version, t);

            // ✅ 清理失败创建的 Runtime
            if (isNewRuntime) {
                LingRuntime failedRuntime = runtimes.remove(lingId);
                if (failedRuntime != null) {
                    try {
                        failedRuntime.shutdown();
                    } catch (Exception e) {
                        log.warn("Failed to cleanup runtime for {}", lingId, e);
                    }
                }
                // 清理存储
                lingSources.remove(lingId);
                lingDefinitionMap.remove(lingId);
            }

            cleanupOnFailure(lingClassLoader, container);
            throw t;
        }
    }

    private LingRuntime createRuntime(String lingId) {
        ExecutorService lingExec = createLingExecutor(lingId);
        return new LingRuntime(
                lingId, lingFrameConfig.getRuntimeConfig(),
                scheduler, lingExec,
                governanceKernel, eventBus, trafficRouter,
                lingServiceInvoker, transactionVerifier, propagators,
                resourceGuard);
    }

    private void cleanupOnFailure(ClassLoader classLoader, LingContainer container) {
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

    private void unregisterProtocolServices(String lingId) {
        protocolServiceRegistry.entrySet().removeIf(entry -> {
            if (entry.getValue().equals(lingId)) {
                log.info("[{}] Unregistered FQSID: {}", lingId, entry.getKey());
                return true;
            }
            return false;
        });
    }

    // ==================== 基础设施创建 ====================

    private ScheduledExecutorService createScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lingframe-Ling-cleaner");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler(
                    (thread, e) -> log.error("Scheduler thread {} error: {}", thread.getName(), e.getMessage()));
            return t;
        });
    }

    /**
     * 为单个单元创建独立线程池（三重约束）
     * <ol>
     * <li>不超过单单元硬上限 (maxThreadsPerLing)</li>
     * <li>不超过全局剩余预算 (globalThreadBudget)</li>
     * <li>最少保底 1 个线程</li>
     * </ol>
     */
    private ExecutorService createLingExecutor(String lingId) {
        int requested = lingFrameConfig.getDefaultThreadsPerLing();
        int maxPerLing = lingFrameConfig.getMaxThreadsPerLing();

        // 约束 1：不超过单单元硬上限
        int actual = Math.min(requested, maxPerLing);

        // 约束 2：不超过全局剩余预算（CAS 扣减）
        int allocated = 0;
        while (true) {
            int remaining = globalThreadBudget.get();
            allocated = Math.min(actual, remaining);
            // 约束 3：最少保底 1 个线程
            allocated = Math.max(allocated, 1);
            int newRemaining = remaining - allocated;
            if (newRemaining < 0)
                newRemaining = 0;
            if (globalThreadBudget.compareAndSet(remaining, newRemaining)) {
                break;
            }
        }

        if (allocated < requested) {
            log.warn("[{}] Thread pool constrained: requested={}, allocated={}, globalRemaining={}",
                    lingId, requested, allocated, globalThreadBudget.get());
        }

        // 记录分配量，卸载时归还
        lingThreadAllocations.put(lingId, allocated);

        log.info("[{}] Created per-ling thread pool: size={}, globalRemaining={}",
                lingId, allocated, globalThreadBudget.get());

        return new ThreadPoolExecutor(
                allocated, allocated,
                KEEP_ALIVE_TIME, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "ling-" + lingId + "-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    t.setUncaughtExceptionHandler(
                            (thread, e) -> log.error("Ling executor thread {} error: {}",
                                    thread.getName(), e.getMessage()));
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 归还单元线程预算
     */
    private void reclaimThreadBudget(String lingId) {
        Integer allocated = lingThreadAllocations.remove(lingId);
        if (allocated != null && allocated > 0) {
            globalThreadBudget.addAndGet(allocated);
            log.info("[{}] Reclaimed thread budget: returned={}, globalRemaining={}",
                    lingId, allocated, globalThreadBudget.get());
        }
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

    private void shutdownExecutorNow(ExecutorService executor) {
        executor.shutdownNow(); // 直接尝试取消所有任务
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Executor did not terminate during shutdownNow");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}