package com.lingframe.starter.configuration;

import com.lingframe.api.context.LingContext;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.audit.AuditManager;
import com.lingframe.core.classloader.SharedApiManager;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.context.DefaultLingContext;
import com.lingframe.core.deploy.DefaultLingDeployService;
import com.lingframe.core.deploy.LingDeployService;
import com.lingframe.core.dev.HotSwapWatcher;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.ling.DefaultLingLifecycleEngine;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.LifecycleEngineConfig;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingResourceManager;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.ling.LingUnloadCoordinator;
import com.lingframe.core.loader.LingDiscoveryService;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.pipeline.FilterRegistryConfig;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.security.ApiOverrideVerifier;
import com.lingframe.core.security.DangerousApiVerifier;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.LeakDetector;
import com.lingframe.core.spi.LingLoaderFactory;
import com.lingframe.core.spi.LingSecurityVerifier;
import com.lingframe.core.resource.JdbcDriverUnloadHook;
import com.lingframe.core.resource.ThreadReferenceUnloadHook;
import com.lingframe.core.resource.JvmShutdownHookUnloadHook;
import com.lingframe.core.resource.RmiTargetUnloadHook;
import com.lingframe.core.resource.LoggingFrameworkUnloadHook;
import com.lingframe.core.resource.DebuggerCaptureUnloadHook;
import java.util.ArrayList;
import java.util.Arrays;
import com.lingframe.core.spi.LingServiceInvoker;
import com.lingframe.core.spi.LingUnloadHook;
import com.lingframe.core.spi.ServiceExporter;
import com.lingframe.core.spi.TrafficRouter;
import com.lingframe.starter.adapter.SpringContainerFactory;
import com.lingframe.starter.event.ServiceExporterListener;
import com.lingframe.starter.processor.LingReferenceInjector;
import com.lingframe.starter.spi.LingContextCustomizer;
import com.lingframe.starter.web.WebInterfaceManager;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 生命周期与治理主链装配切片。
 */
@Configuration
public class LingFrameLifecycleBeansConfiguration {

    private static final AtomicBoolean BOOTSTRAP_DONE = new AtomicBoolean(false);

    @Bean
    public ContainerFactory containerFactory(ApplicationContext parentContext,
            WebInterfaceManager webInterfaceManager,
            ObjectProvider<List<LingContextCustomizer>> customizersProvider,
            List<LingUnloadHook> unloadHooks) {
        List<LingContextCustomizer> customizers = customizersProvider.getIfAvailable(Collections::emptyList);
        return new SpringContainerFactory(parentContext, webInterfaceManager, customizers, unloadHooks);
    }

    @Bean
    @ConditionalOnMissingBean
    public LingLifecycleEngine lingLifecycleEngine(ContainerFactory containerFactory,
            PermissionService permissionService,
            LingLoaderFactory lingLoaderFactory,
            ObjectProvider<List<LingSecurityVerifier>> verifiersProvider,
            EventBus eventBus,
            LingFrameConfig lingFrameConfig,
            LingRepository lingRepository,
            LingServiceRegistry lingServiceRegistry,
            InvocationPipelineEngine pipelineEngine,
            List<LingUnloadHook> unloadHooks,
            LingResourceManager lingResourceManager,
            LeakDetector leakDetector,
            RuntimeCoordinator runtimeCoordinator,
            ObjectProvider<MetricsCollector> metricsCollectorProvider,
            ObjectProvider<GovernanceMetricsCollector> governanceMetricsCollectorProvider) {
        List<LingSecurityVerifier> verifiers = verifiersProvider.getIfAvailable(Collections::emptyList);
        // 微内核解耦：安全验证器由组装层注入默认实现，内核不再自动添加
        List<LingSecurityVerifier> allVerifiers = new ArrayList<>(verifiers);
        if (lingFrameConfig == null || lingFrameConfig.isApiOverrideCheckEnabled()) {
            if (allVerifiers.stream().noneMatch(v -> v instanceof ApiOverrideVerifier)) {
                allVerifiers.add(0, new ApiOverrideVerifier());
            }
        }
        if (allVerifiers.stream().noneMatch(v -> v instanceof DangerousApiVerifier)) {
            allVerifiers.add(new DangerousApiVerifier());
        }
        // 生态桶：Spring 生态清理 Hook（由 Spring Bean 注入）
        // JVM 桶：JVM 级 Hook，桶内并行执行
        // 涵盖：JDBC Driver、线程引用/H2/Timer/线程池、ShutdownHook、RMI Target、日志框架、IDE 调试器缓存
        List<LingUnloadHook> jvmHooks = Arrays.asList(
                new JdbcDriverUnloadHook(),
                new ThreadReferenceUnloadHook(),
                new JvmShutdownHookUnloadHook(),
                new RmiTargetUnloadHook(),
                new LoggingFrameworkUnloadHook(),
                new DebuggerCaptureUnloadHook());
        LingUnloadCoordinator unloadCoordinator = new LingUnloadCoordinator(
                pipelineEngine, unloadHooks, jvmHooks, lingResourceManager, leakDetector);

        // 微内核解耦：指标/告警由组装层注入，内核不直接构造
        MetricsCollector mc = metricsCollectorProvider.getIfAvailable();
        GovernanceMetricsCollector gmc = governanceMetricsCollectorProvider.getIfAvailable();

        DefaultLingLifecycleEngine engine = new DefaultLingLifecycleEngine(LifecycleEngineConfig.builder()
                .containerFactory(containerFactory)
                .permissionService(permissionService)
                .lingLoaderFactory(lingLoaderFactory)
                .verifiers(allVerifiers)
                .eventBus(eventBus)
                .lingFrameConfig(lingFrameConfig)
                .lingRepository(lingRepository)
                .lingServiceRegistry(lingServiceRegistry)
                .pipelineEngine(pipelineEngine)
                .lingResourceManager(lingResourceManager)
                .unloadCoordinator(unloadCoordinator)
                .runtimeCoordinator(runtimeCoordinator)
                .metricsCollector(mc)
                .governanceMetricsCollector(gmc)
                .build());
        return engine;
    }

    @Bean
    @ConditionalOnMissingBean
    public FilterRegistry filterRegistry(LingRepository lingRepository,
            InvokableMethodCache methodCache,
            PermissionService permissionService,
            ObjectProvider<LingServiceInvoker> invokerProvider,
            ObjectProvider<GovernanceArbitrator> arbitratorProvider,
            ObjectProvider<MetricsCollector> metricsCollectorProvider,
            ObjectProvider<GovernanceMetricsCollector> governanceMetricsCollectorProvider,
            TrafficRouter trafficRouter,
            EventBus eventBus,
            RuntimeCoordinator runtimeCoordinator,
            LingServiceRegistry lingServiceRegistry) {
        LingServiceInvoker invoker = invokerProvider.getIfAvailable();
        GovernanceArbitrator arbitrator = arbitratorProvider.getIfAvailable();
        MetricsCollector metricsCollector = metricsCollectorProvider.getIfAvailable();
        GovernanceMetricsCollector governanceMetricsCollector = governanceMetricsCollectorProvider.getIfAvailable();
        FilterRegistry registry = new FilterRegistry(FilterRegistryConfig.builder()
                .methodCache(methodCache)
                .permissionService(permissionService)
                .serviceInvoker(invoker)
                .governanceArbitrator(arbitrator)
                .lingRepository(lingRepository)
                .trafficRouter(trafficRouter)
                .eventBus(eventBus)
                .serviceRegistry(lingServiceRegistry)
                .metricsCollector(metricsCollector)
                .runtimeCoordinator(runtimeCoordinator)
                .governanceMetricsCollector(governanceMetricsCollector)
                .build());
        registry.loadSpiFilters(Thread.currentThread().getContextClassLoader());
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public InvocationPipelineEngine invocationPipelineEngine(FilterRegistry filterRegistry) {
        return new InvocationPipelineEngine(filterRegistry);
    }

    @Bean
    public LingDiscoveryService lingDiscoveryService(LingFrameConfig config, LingLifecycleEngine lifecycleEngine) {
        return new LingDiscoveryService(config, lifecycleEngine);
    }

    @Bean
    public LingDeployService lingDeployService(LingLifecycleEngine lifecycleEngine) {
        return new DefaultLingDeployService(lifecycleEngine);
    }

    @Bean(destroyMethod = "shutdown")
    public ServiceExporterListener serviceExporterListener(EventBus eventBus,
            LingRepository lingRepository,
            LingServiceRegistry lingServiceRegistry,
            ObjectProvider<List<ServiceExporter>> exportersProvider) {
        List<ServiceExporter> exporters = exportersProvider.getIfAvailable(Collections::emptyList);
        return new ServiceExporterListener(eventBus, lingRepository, lingServiceRegistry, exporters);
    }

    @Bean(destroyMethod = "shutdown")
    public SharedApiManager sharedApiManager(LingFrameConfig config) {
        return new SharedApiManager(Thread.currentThread().getContextClassLoader(), config);
    }

    @Bean
    public ApplicationRunner lingScannerRunner(LingDiscoveryService discoveryService,
            SharedApiManager sharedApiManager) {
        return args -> {
            if (!BOOTSTRAP_DONE.compareAndSet(false, true)) {
                return;
            }
            sharedApiManager.preloadFromConfig();
            sharedApiManager.freezeSharedBoundary();
            discoveryService.scanAndLoad();
        };
    }

    /**
     * 灵核上下文关闭时重置静态状态，确保下一个灵核能正常启动。
     * 同时关闭 AuditManager 线程池，确保审计记录在容器关闭前落盘。
     */
    @Bean
    DisposableBean lingFrameStaticStateResetter() {
        return () -> {
            AuditManager.shutdown();
            BOOTSTRAP_DONE.set(false);
            LingFrameConfig.clear();
        };
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "lingframe", name = "dev-mode", havingValue = "true")
    public HotSwapWatcher hotSwapWatcher(LingLifecycleEngine lifecycleEngine,
            LingRepository lingRepository,
            EventBus eventBus,
            LeakDetector leakDetector) {
        // 循环依赖解法：watcher 先以 null engine 构造，再通过 setLifecycleEngine 延迟绑定。
        // engine 构造时不持有 watcher（dev-mode 按需激活），watcher 创建后反向绑定 engine。
        HotSwapWatcher watcher = new HotSwapWatcher(null, lingRepository, eventBus, leakDetector);
        watcher.setLifecycleEngine(lifecycleEngine);
        return watcher;
    }

    @Bean
    public LingContext lingCoreContext(LingRepository lingRepository,
            LingServiceRegistry lingServiceRegistry,
            InvocationPipelineEngine pipelineEngine,
            PermissionService permissionService,
            EventBus eventBus) {
        return new DefaultLingContext("lingcore-app",
                lingRepository,
                lingServiceRegistry,
                pipelineEngine,
                permissionService,
                eventBus);
    }

    @Bean
    public LingReferenceInjector lingReferenceInjector(LingContext lingContext) {
        // 传入灵核级 LingContext，确保 BPP 在 BeforeInitialization 阶段就能拿到 ctx 做注入；
        // AfterInitialization 阶段兜底扫应对灵核级 BPP 时序问题。
        return new LingReferenceInjector("lingcore-app", lingContext);
    }

    @Bean
    public WebInterfaceManager webInterfaceManager(LingRepository lingRepository,
            TrafficRouter trafficRouter,
            ObjectProvider<MetricsCollector> metricsCollectorProvider) {
        return new WebInterfaceManager(lingRepository, trafficRouter, metricsCollectorProvider);
    }
}
