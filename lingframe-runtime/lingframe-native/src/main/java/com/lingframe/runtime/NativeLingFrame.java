package com.lingframe.runtime;

import com.lingframe.api.context.LingContext;
import com.lingframe.api.exception.ServiceUnavailableException;
import com.lingframe.core.audit.AuditManager;
import com.lingframe.core.classloader.DefaultLingLoaderFactory;
import com.lingframe.core.classloader.SharedApiClassLoader;
import com.lingframe.core.classloader.SharedApiManager;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.context.DefaultLingContext;
import com.lingframe.core.dev.HotSwapWatcher;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.ling.DefaultLingLifecycleEngine;
import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.DefaultLingResourceManager;
import com.lingframe.core.ling.DefaultLingServiceRegistry;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.resource.DefaultLeakDetector;
import com.lingframe.core.spi.LeakDetector;
import com.lingframe.core.ling.LingFrameRuntime;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.resource.BasicResourceGuard;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.ling.LingUnloadCoordinator;
import com.lingframe.core.loader.LingDiscoveryService;
import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.pipeline.LatestVersionPolicy;
import com.lingframe.core.router.CanaryRouter;
import com.lingframe.core.security.DangerousApiVerifier;
import com.lingframe.core.security.DefaultPermissionService;
import com.lingframe.core.spi.LingServiceInvoker;
import com.lingframe.core.invoker.FastLingServiceInvoker;
import com.lingframe.runtime.adapter.NativeContainerFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 灵珑 Native 启动器。
 * 灵核应用通过此类一键启动框架。
 * <p>
 * 生命周期对称性：
 * <ul>
 *   <li>{@link #start} → 初始化所有组件、扫描灵元、注册关闭钩子</li>
 *   <li>{@link #shutdown} → 卸载灵元、关闭 EventBus/AuditManager、释放资源</li>
 * </ul>
 */
@Slf4j
public class NativeLingFrame {

    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static LingFrameRuntime GLOBAL_LIFECYCLE_ENGINE;
    private static LingContext HOST_CONTEXT;
    private static HotSwapWatcher HOT_SWAP_WATCHER;
    private static DefaultLingResourceManager RESOURCE_MANAGER;
    private static RuntimeCoordinator RUNTIME_COORDINATOR;
    private static SharedApiManager SHARED_API_MANAGER;
    private static LeakDetector LEAK_DETECTOR;
    private static EventBus EVENT_BUS;
    private static LingRepository LING_REPOSITORY;

    /**
     * 使用默认配置启动灵珑。
     */
    public static LingFrameRuntime start() {
        return start(LingFrameConfig.current());
    }

    /**
     * 使用自定义配置启动灵珑。
     */
    public static LingFrameRuntime start(LingFrameConfig config) {
        if (started.get()) {
            log.warn("LingFrame is already started.");
            return GLOBAL_LIFECYCLE_ENGINE;
        }

        long start = System.currentTimeMillis();
        log.info("Starting LingFrame Native Runtime...");

        // 准备基础设施
        EventBus eventBus = new EventBus();
        EVENT_BUS = eventBus;
        RUNTIME_COORDINATOR = new RuntimeCoordinator(eventBus);
        RUNTIME_COORDINATOR.start();

        // 准备核心组件
        DefaultPermissionService permissionService = new DefaultPermissionService(eventBus);
        DefaultLingLoaderFactory loaderFactory = new DefaultLingLoaderFactory();
        SHARED_API_MANAGER = new SharedApiManager(Thread.currentThread().getContextClassLoader(), config);
        SHARED_API_MANAGER.preloadFromConfig();
        SHARED_API_MANAGER.freezeSharedBoundary();

        // 创建 Native 专用的容器工厂
        NativeContainerFactory containerFactory = new NativeContainerFactory();

        LingRepository lingRepository = new DefaultLingRepository();
        LING_REPOSITORY = lingRepository;
        LingServiceRegistry lingServiceRegistry = new DefaultLingServiceRegistry();

        InvokableMethodCache invokableMethodCache = new InvokableMethodCache();
        LingServiceInvoker invoker = resolveInvoker(Thread.currentThread().getContextClassLoader());

        MetricsCollector metricsCollector = new MetricsCollector(lingRepository);
        GovernanceMetricsCollector governanceMetricsCollector = new GovernanceMetricsCollector();

        FilterRegistry filterRegistry = new FilterRegistry(invokableMethodCache, permissionService, invoker, null);
        CanaryRouter canaryRouter = new CanaryRouter(new LatestVersionPolicy());
        filterRegistry.initialize(lingRepository, canaryRouter, eventBus,
                metricsCollector, RUNTIME_COORDINATOR, governanceMetricsCollector);
        InvocationPipelineEngine pipelineEngine = new InvocationPipelineEngine(
                filterRegistry);

        RESOURCE_MANAGER = new DefaultLingResourceManager(lingRepository, eventBus, invokableMethodCache);
        LEAK_DETECTOR = new DefaultLeakDetector(eventBus, config);
        LingUnloadCoordinator unloadCoordinator = new LingUnloadCoordinator(
                pipelineEngine,
                Collections.singletonList(new BasicResourceGuard(eventBus)),
                RESOURCE_MANAGER,
                LEAK_DETECTOR);

        DefaultLingLifecycleEngine lifecycleEngine = new DefaultLingLifecycleEngine(
                containerFactory,
                permissionService,
                loaderFactory,
                Collections.singletonList(new DangerousApiVerifier()),
                eventBus,
                config,
                lingRepository,
                lingServiceRegistry,
                pipelineEngine,
                RESOURCE_MANAGER,
                unloadCoordinator,
                RUNTIME_COORDINATOR);

        lifecycleEngine.setCanaryConfigurable(canaryRouter);
        lifecycleEngine.setMetricsCollector(metricsCollector);
        lifecycleEngine.setGovernanceMetricsCollector(governanceMetricsCollector);

        if (config != null && config.isDevMode()) {
            HOT_SWAP_WATCHER = new HotSwapWatcher(lifecycleEngine, lingRepository, eventBus, LEAK_DETECTOR);
            lifecycleEngine.setHotSwapWatcher(HOT_SWAP_WATCHER);
        }

        // 注册一个特殊的 "lingcore-app" 上下文
        HOST_CONTEXT = new DefaultLingContext("lingcore-app", lingRepository, lingServiceRegistry, pipelineEngine,
                permissionService, eventBus);

        // 自动扫描灵元
        if (config.getLingRoots() != null || config.getLingHome() != null) {
            LingDiscoveryService discoveryService = new LingDiscoveryService(config, lifecycleEngine);
            log.info("Executing initial ling scan...");
            discoveryService.scanAndLoad();
        }

        // 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(NativeLingFrame::shutdown));

        GLOBAL_LIFECYCLE_ENGINE = lifecycleEngine;
        started.set(true);

        log.info("LingFrame Native started in {} ms", System.currentTimeMillis() - start);

        return lifecycleEngine;
    }

    /**
     * 关闭 Native runtime 并清理全局状态。
     * <p>
     * 关闭顺序与 Spring 路径对称：
     * <ol>
     *   <li>卸载所有已安装灵元</li>
     *   <li>关闭 HotSwapWatcher</li>
     *   <li>关闭 LeakDetector</li>
     *   <li>关闭 ResourceManager</li>
     *   <li>关闭 RuntimeCoordinator</li>
     *   <li>关闭 EventBus</li>
     *   <li>关闭 AuditManager</li>
     *   <li>关闭 SharedApiManager</li>
     * </ol>
     */
    public static synchronized void shutdown() {
        if (!started.get()) {
            return;
        }

        log.info("LingFrame shutting down...");

        // 1. 卸载所有已安装灵元
        if (GLOBAL_LIFECYCLE_ENGINE != null && LING_REPOSITORY != null) {
            try {
                String[] lingIds = LING_REPOSITORY.getAllRuntimes().stream()
                        .map(LingRuntime::getLingId).toArray(String[]::new);
                for (String lingId : lingIds) {
                    try {
                        GLOBAL_LIFECYCLE_ENGINE.undeploy(lingId);
                        log.info("Uninstalled ling: {}", lingId);
                    } catch (Exception e) {
                        log.warn("Failed to uninstall ling: {}", lingId, e);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to enumerate lings for uninstall", e);
            }
        }

        // 2. 关闭 HotSwapWatcher
        if (HOT_SWAP_WATCHER != null) {
            HOT_SWAP_WATCHER.shutdown();
            HOT_SWAP_WATCHER = null;
        }
        // 3. 关闭 LeakDetector
        if (LEAK_DETECTOR != null) {
            LEAK_DETECTOR.shutdown();
            LEAK_DETECTOR = null;
        }
        // 4. 关闭 ResourceManager
        if (RESOURCE_MANAGER != null) {
            RESOURCE_MANAGER.shutdown();
            RESOURCE_MANAGER = null;
        }
        // 5. 关闭 RuntimeCoordinator
        if (RUNTIME_COORDINATOR != null) {
            RUNTIME_COORDINATOR.stop();
            RUNTIME_COORDINATOR = null;
        }
        // 6. 关闭 EventBus
        if (EVENT_BUS != null) {
            EVENT_BUS.shutdown();
            EVENT_BUS = null;
        }
        // 7. 关闭 AuditManager
        AuditManager.shutdown();
        // 8. 关闭 SharedApiManager
        if (SHARED_API_MANAGER != null) {
            SHARED_API_MANAGER.shutdown();
            SHARED_API_MANAGER = null;
        } else {
            SharedApiClassLoader.resetInstance();
        }

        GLOBAL_LIFECYCLE_ENGINE = null;
        HOST_CONTEXT = null;
        LING_REPOSITORY = null;
        started.set(false);

        log.info("LingFrame shutdown complete.");
    }

    private static LingServiceInvoker resolveInvoker(ClassLoader hostClassLoader) {
        try {
            ServiceLoader<LingServiceInvoker> loader = ServiceLoader.load(LingServiceInvoker.class, hostClassLoader);
            for (LingServiceInvoker invoker : loader) {
                return invoker;
            }
        } catch (Exception ignored) {
            // 如解析失败，则回退到默认实现
        }
        return new FastLingServiceInvoker();
    }

    /**
     * 获取灵核上下文，用于 invoke 调用。
     */
    public static LingContext getHostContext() {
        if (!started.get()) {
            throw new ServiceUnavailableException("lingcore-app", "LingFrame not started");
        }
        return HOST_CONTEXT;
    }
}
