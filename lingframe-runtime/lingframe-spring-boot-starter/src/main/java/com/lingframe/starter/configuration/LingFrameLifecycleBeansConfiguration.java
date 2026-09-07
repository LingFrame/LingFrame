package com.lingframe.starter.configuration;

import com.lingframe.api.constant.LingCoreConstants;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.security.PermissionService;
import com.lingframe.api.storage.ManagedDataSourceRegistry;
import com.lingframe.core.audit.AuditManager;
import com.lingframe.core.classloader.SharedApiManager;
import com.lingframe.core.classloader.LingClassLoader;
import com.lingframe.starter.adapter.LingCoreContainerAdapter;
import com.lingframe.starter.classloader.EcosystemParentPackages;
import com.lingframe.starter.config.LingFrameProperties;
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
import com.lingframe.core.spi.TransactionBindingHook;
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
import com.lingframe.starter.storage.DefaultManagedDataSourceRegistry;
import com.lingframe.starter.transaction.SpringTransactionBindingHook;
import com.lingframe.infra.storage.proxy.LingDataSourceProxy;
import com.lingframe.starter.web.WebInterfaceManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 生命周期与治理主链装配切片。
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
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

    /**
     * 受管数据源独立总线（模式 1 供给端装配）。
     * <p>
     * 灵核侧静态托管数据源（若有）经 {@code DataSourceWrapperProcessor} 包装为
     * {@code LingDataSourceProxy} 后，以 {@code dataSourceId="default"} 注册到总线；
     * 灵核 0 存储场景（无 DataSource Bean）时总线保持空，由模式 3 存储灵元后续注册。
     * <p>
     * 懒解析：总线 Bean 创建时灵核 DataSource 可能尚未初始化，故在注册 lambda 内
     * 经 {@link ObjectProvider} 延迟获取——Bean 创建顺序无关。
     */
    @Bean
    public ManagedDataSourceRegistry managedDataSourceRegistry(
            ObjectProvider<DataSource> coreDataSourceProvider,
            PermissionService permissionService) {
        DefaultManagedDataSourceRegistry registry = new DefaultManagedDataSourceRegistry();
        registry.register("default", () -> {
            DataSource core = coreDataSourceProvider.getIfAvailable();
            if (core == null) {
                // 灵核 0 存储：lookup("default") 返回 null，灵元分支 B 走不到注入
                return null;
            }
            // DataSourceWrapperProcessor 已把灵核 DataSource 包装为 LingDataSourceProxy
            // （该实例即灵核 DataSourceTransactionManager 持有的 TSM 资源键）。
            // 【关键】必须【同实例】提升身份后返回——TSM 以实例为键，若在此复刻新代理，
            // 灵核事务管理器与总线查找将各持一份实例，穿透提取不到连接而静默失效。
            // 未包装时（BPP 未生效的兜底）才新建带身份的受管代理。
            if (core instanceof LingDataSourceProxy) {
                ((LingDataSourceProxy) core).promoteToManaged("default");
                return core;
            }
            log.warn("[LingFrame] Core DataSource is not wrapped by DataSourceWrapperProcessor ({}), "
                    + "creating fallback proxy. Transaction propagation will NOT match TSM key!",
                    core.getClass().getName());
            return new LingDataSourceProxy(core, permissionService, "default");
        });
        return registry;
    }

    /**
     * 卸载清理协调器独立暴露为 Bean：供 Dashboard 等观测方读取卸载耗时指标
     * （最近一次卸载耗时 / 累计卸载次数），并统一提供卸载编排与清理完成信号（awaitCleanup）。
     */
    @Bean
    @ConditionalOnMissingBean
    public LingUnloadCoordinator lingUnloadCoordinator(InvocationPipelineEngine pipelineEngine,
            List<LingUnloadHook> unloadHooks,
            LingResourceManager lingResourceManager,
            LeakDetector leakDetector) {
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
        return new LingUnloadCoordinator(pipelineEngine, unloadHooks, jvmHooks, lingResourceManager, leakDetector);
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
            LingUnloadCoordinator unloadCoordinator,
            LingResourceManager lingResourceManager,
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
            ProviderWeightRouter providerWeightRouter,
            ManagedDataSourceRegistry managedDataSourceRegistry,
            LingFrameProperties lingFrameProperties) {
        LingServiceInvoker invoker = invokerProvider.getIfAvailable();
        GovernanceArbitrator arbitrator = arbitratorProvider.getIfAvailable();
        MetricsCollector metricsCollector = metricsCollectorProvider.getIfAvailable();
        GovernanceMetricsCollector governanceMetricsCollector = governanceMetricsCollectorProvider.getIfAvailable();

        // 事务状态提取 hook：受管数据源总线存在时构造 Spring 实现并注入穿透过滤器；
        // 灵核 0 存储/纯 core 场景总线为 null，hook 为 null -> TransactionPropagationFilter 降级为无穿透
        TransactionBindingHook transactionBindingHook =
                managedDataSourceRegistry != null ? new SpringTransactionBindingHook(managedDataSourceRegistry) : null;

        // 事务穿透总开关：映射 lingframe.tx.propagation.enabled（默认 true）。
        // 关闭时 core 侧过滤器直接放行、灵元侧不注册受管事务管理器——应急降级路径
        boolean propagationEnabled = lingFrameProperties.getTx().getPropagation().isEnabled();

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
                .transactionBindingHook(transactionBindingHook)
                .propagationEnabled(propagationEnabled)
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

    /**
     * 检测灵核根事务管理器类型，非 JDBC 型时输出启动期 WARN（穿透不激活）。
     * <p>
     * 反射实现：装配类可能运行在无 spring-tx 的 classpath（springdoc / 纯 Web 测试场景），
     * 方法签名若直接引用 {@code PlatformTransactionManager} 会在容器解析 Bean 方法参数类型时
     * 抛 {@code TypeNotPresentException}——用反射 + 判空在类缺失时静默跳过检测。
     *
     * @param applicationContext 灵核主容器（用于按类型查找事务管理器 Bean）
     */
    private void warnIfRootTransactionManagerNotJdbc(ApplicationContext applicationContext) {
        try {
            Class<?> ptmType = Class.forName("org.springframework.transaction.PlatformTransactionManager");
            Class<?> jdbcTxType = Class.forName("org.springframework.jdbc.datasource.DataSourceTransactionManager");
            Object txManager = applicationContext.getBeanProvider(ptmType).getIfAvailable();
            if (txManager != null && !jdbcTxType.isInstance(txManager)) {
                log.warn("[LingFrame] Core PlatformTransactionManager is not DataSourceTransactionManager ({}), "
                        + "transaction propagation will NOT be active: managed ling SQL runs on independent connections. "
                        + "Configure a JDBC transaction manager to enable cross-ling strong consistency.",
                        txManager.getClass().getName());
            }
        } catch (ClassNotFoundException e) {
            // spring-tx 不在 classpath（springdoc / 纯 Web 测试场景）：跳过 DTM 类型检测，不抛错
            log.debug("[LingFrame] spring-tx not on classpath, skip transaction manager type detection");
        }
    }

    /**
     * 根事务管理器类型检测：在所有单例初始化完成后（SmartInitializingSingleton）执行检测，
     * 避免在 Bean 装配阶段（如 filterRegistry @Bean 方法内）过早触发 transactionManager
     * 和 dataSource 实例化，造成 BeanPostProcessor 脱靶。
     */
    @Bean
    public SmartInitializingSingleton rootTransactionManagerChecker(ApplicationContext applicationContext) {
        return () -> warnIfRootTransactionManagerNotJdbc(applicationContext);
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
            LingFrameProperties properties) {
        // Web 请求指标统一由 LingWebGovernanceFilter 计量（单点），此处不再注入 MetricsCollector（C1）；
        // 转发前缀白名单（C10）来自 lingframe.trusted-forwarded-prefixes，空则不采信客户端转发头
        return new WebInterfaceManager(lingRepository, trafficRouter,
                properties != null ? properties.getTrustedForwardedPrefixes() : Collections.emptyList());
    }
}
