package com.lingframe.starter.configuration;

import com.lingframe.api.constant.LingCoreConstants;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.audit.AuditManager;
import com.lingframe.core.classloader.SharedApiManager;
import com.lingframe.core.classloader.LingClassLoader;
import com.lingframe.starter.adapter.LingCoreContainerAdapter;
import com.lingframe.starter.classloader.EcosystemParentPackages;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.context.DefaultLingContext;
import com.lingframe.core.deploy.DefaultLingDeployService;
import com.lingframe.core.deploy.LingDeployService;
import com.lingframe.core.dev.HotSwapWatcher;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.ling.DefaultLingLifecycleEngine;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.LifecycleEngineConfig;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingResourceManager;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.ling.LingUnloadCoordinator;
import com.lingframe.core.loader.LingDiscoveryService;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.metrics.ProviderMetricsCollector;
import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.pipeline.FilterRegistryConfig;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.routing.ProviderWeightRouter;
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
            // strictMode 与 trustedLibPrefixes 均可配置，开发环境可关严格模式或豁免依赖库前缀
            boolean strictMode = lingFrameConfig != null && lingFrameConfig.isStrictSecurityMode();
            allVerifiers.add(new DangerousApiVerifier(strictMode,
                    lingFrameConfig != null ? lingFrameConfig.getTrustedLingIds() : Collections.emptyList(),
                    lingFrameConfig != null ? lingFrameConfig.getTrustedLibPrefixes() : Collections.emptyList()));
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
    public ProviderWeightRouter providerWeightRouter() {
        // L0 provider 级权重路由器，Dashboard 通过此 Bean 下发运行期权重覆盖
        return new ProviderWeightRouter();
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
            LingServiceRegistry lingServiceRegistry,
            LingFrameConfig lingFrameConfig,
            LocalGovernanceRegistry governanceRegistry,
            ProviderWeightRouter providerWeightRouter) {
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
                .lingFrameInfo(lingFrameConfig)
                .governanceRegistry(governanceRegistry)
                .providerWeightRouter(providerWeightRouter)
                .build());
        registry.loadSpiFilters(Thread.currentThread().getContextClassLoader());
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public ProviderMetricsCollector providerMetricsCollector() {
        // Provider 维度调用指标收集器，InvocationPipelineEngine 在每次调用后写入
        return new ProviderMetricsCollector();
    }

    @Bean
    @ConditionalOnMissingBean
    public InvocationPipelineEngine invocationPipelineEngine(FilterRegistry filterRegistry,
            ProviderMetricsCollector providerMetricsCollector) {
        // ProviderMetricsCollector 由同切片的 providerMetricsCollector() 装配（@ConditionalOnMissingBean 兜底），
        // 二者必同时存在；dashboard 独立运行场景由 DashboardAutoConfiguration 自行装配该 Bean。
        return new InvocationPipelineEngine(filterRegistry, providerMetricsCollector);
    }

    @Bean
    public LingDiscoveryService lingDiscoveryService(LingFrameConfig config, LingLifecycleEngine lifecycleEngine,
            LingRepository lingRepository) {
        return new LingDiscoveryService(config, lifecycleEngine, lingRepository);
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
            // 生态环境委派注入：core 不替灵核决策「该共享什么」，由适配层注入。
            // starter 经灵核生态环境（Spring/Jackson/Logback/Log4j2）必须走父委派，
            // 避免灵元自带这些生态的副本与灵核实例并存造成 ClassCastException。
            LingClassLoader.addParentDelegatePackages(EcosystemParentPackages.ecosystemDefaults());
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
    public LingCoreContainerAdapter lingCoreContainerAdapter(ApplicationContext applicationContext) {
        // 灵核 ApplicationContext 适配壳,使灵核作为 CORE provider 参与 Pipeline 路由
        return new LingCoreContainerAdapter(applicationContext);
    }

    @Bean
    public LingInstance lingCoreInstance(LingLifecycleEngine lingLifecycleEngine,
            LingCoreContainerAdapter lingCoreContainerAdapter) {
        // 装配灵核 LingRuntime + LingInstance + 推进 READY/ACTIVE
        // 灵核实例 version=permanent,永久 READY,不支持热加载/热卸载
        // 注:Bean 注册为 LingLifecycleEngine 接口类型,实际实现是 DefaultLingLifecycleEngine
        DefaultLingLifecycleEngine engine = (DefaultLingLifecycleEngine) lingLifecycleEngine;
        return engine.bootstrapLingCoreInstance(
                LingCoreConstants.LINGCORE_LING_ID,
                lingCoreContainerAdapter,
                LingCoreConstants.LINGCORE_VERSION);
    }

    @Bean
    public LingContext lingCoreContext(LingInstance lingCoreInstance,
            LingRepository lingRepository,
            LingServiceRegistry lingServiceRegistry,
            InvocationPipelineEngine pipelineEngine,
            PermissionService permissionService,
            EventBus eventBus) {
        // 用灵元部署构造函数(instance 非空),使 registerProtocolService 做实例绑定
        // 这样 Pipeline 路由命中 lingcore-app 后,TerminalInvokerFilter 能通过 instance.getContainer().getBean(...) 取到灵核 Bean
        return new DefaultLingContext(
                lingCoreInstance,
                lingRepository,
                lingServiceRegistry,
                pipelineEngine,
                permissionService,
                eventBus);
    }

    @Bean
    public LingReferenceInjector lingReferenceInjector(LingContext lingContext) {
        // 传入灵核级 LingContext,确保 BPP 在 BeforeInitialization 阶段就能拿到 ctx 做注入;
        // AfterInitialization 阶段兜底扫应对灵核级 BPP 时序问题。
        return new LingReferenceInjector(LingCoreConstants.LINGCORE_LING_ID, lingContext);
    }

    @Bean
    public WebInterfaceManager webInterfaceManager(LingRepository lingRepository,
            TrafficRouter trafficRouter,
            ObjectProvider<MetricsCollector> metricsCollectorProvider) {
        return new WebInterfaceManager(lingRepository, trafficRouter, metricsCollectorProvider);
    }
}
