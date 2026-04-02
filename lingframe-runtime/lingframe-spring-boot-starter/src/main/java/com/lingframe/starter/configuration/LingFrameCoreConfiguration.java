package com.lingframe.starter.configuration;

import com.lingframe.api.context.LingContext;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.classloader.DefaultLingLoaderFactory;
import com.lingframe.core.classloader.SharedApiManager;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.context.DefaultLingContext;
import com.lingframe.core.deploy.DefaultLingDeployService;
import com.lingframe.core.deploy.LingDeployService;
import com.lingframe.core.dev.HotSwapWatcher;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.governance.GovernancePermissionSynchronizer;
import com.lingframe.core.governance.LingCoreGovernanceRule;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ConfigurableApplicationContext;
import com.lingframe.core.governance.provider.StandardGovernancePolicyProvider;
import com.lingframe.core.invoker.FastLingServiceInvoker;
import com.lingframe.core.spi.LingServiceInvoker;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.*;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.resource.DefaultLeakDetector;
import com.lingframe.core.loader.LingDiscoveryService;
import com.lingframe.core.pipeline.FilterRegistry;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.router.LabelMatchRouter;
import com.lingframe.core.security.DefaultPermissionService;
import com.lingframe.core.spi.*;
import com.lingframe.infra.cache.configuration.CaffeineWrapperProcessor;
import com.lingframe.infra.cache.configuration.RedisWrapperProcessor;
import com.lingframe.infra.cache.configuration.SpringCacheWrapperProcessor;
import com.lingframe.infra.storage.configuration.DataSourceWrapperProcessor;
import com.lingframe.starter.adapter.SpringContainerFactory;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.event.ServiceExporterListener;
import com.lingframe.starter.processor.LingCoreBeanGovernanceProcessor;
import com.lingframe.starter.processor.LingReferenceInjector;
import com.lingframe.starter.resource.SpringBasicResourceGuard;
import com.lingframe.starter.resource.StorageResourceGuard;
import com.lingframe.starter.spi.LingContextCustomizer;
import com.lingframe.starter.web.LingRepeatableReadFilter;
import com.lingframe.starter.web.WebInterfaceManager;
import com.lingframe.starter.web.LingOpenApiCustomizer;
import com.lingframe.starter.web.LingSpringDocCustomizerBridge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 灵珑公共核心配置，且与 Spring Boot 版本无关
 * <p>
 * 所有与 Spring Boot 版本无关的 Bean 定义集中于此。
 * 版本特定的 Starter 通过 {@code @Import(LingFrameCoreConfiguration.class)} 引入。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(LingFrameProperties.class)
@Import({
        DataSourceWrapperProcessor.class,
        SpringCacheWrapperProcessor.class,
        CaffeineWrapperProcessor.class,
        RedisWrapperProcessor.class,
        LingCoreBeanGovernanceProcessor.class
})
public class LingFrameCoreConfiguration {

    private static final AtomicBoolean BOOTSTRAP_DONE = new AtomicBoolean(false);

    @Bean
    @ConditionalOnMissingBean
    public EventBus eventBus() {
        return new EventBus();
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnMissingBean
    public RuntimeCoordinator runtimeCoordinator(EventBus eventBus) {
        return new RuntimeCoordinator(eventBus);
    }

    @Bean
    public LocalGovernanceRegistry localGovernanceRegistry(EventBus eventBus) {
        return new LocalGovernanceRegistry(eventBus);
    }

    @Bean
    @ConditionalOnMissingBean(LingLoaderFactory.class)
    public LingLoaderFactory defaultLingLoaderFactory() {
        return new DefaultLingLoaderFactory();
    }

    @Bean
    public StandardGovernancePolicyProvider standardGovernancePolicyProvider(
            LocalGovernanceRegistry registry,
            LingFrameProperties properties) {

        List<LingCoreGovernanceRule> coreRules = new ArrayList<>();
        if (properties.getRules() != null) {
            for (LingFrameProperties.GovernanceRule r : properties.getRules()) {
                coreRules.add(LingCoreGovernanceRule.builder()
                        .pattern(r.getPattern())
                        .permission(r.getPermission())
                        .accessType(r.getAccess())
                        .auditEnabled(r.getAudit())
                        .auditAction(r.getAuditAction())
                        .timeout(r.getTimeout())
                        .build());
            }
        }

        return new StandardGovernancePolicyProvider(registry, coreRules);
    }

    @Bean
    public GovernanceArbitrator governanceArbitrator(List<GovernancePolicyProvider> providers) {
        return new GovernanceArbitrator(providers);
    }

    @Bean
    @ConditionalOnMissingBean
    public PermissionService permissionService(EventBus eventBus) {
        return new DefaultPermissionService(eventBus);
    }

    @Bean
    @ConditionalOnMissingBean
    public LingRepository lingRepository() {
        return new DefaultLingRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public LingServiceRegistry lingServiceRegistry() {
        return new DefaultLingServiceRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public InvokableMethodCache invokableMethodCache() {
        return new InvokableMethodCache();
    }

    @Bean
    @ConditionalOnMissingBean
    public LingServiceInvoker lingServiceInvoker() {
        return new FastLingServiceInvoker();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public LingResourceManager lingResourceManager(LingRepository lingRepository, EventBus eventBus,
                                                   InvokableMethodCache methodCache) {
        return new DefaultLingResourceManager(lingRepository, eventBus, methodCache);
    }

    @Bean
    public ContainerFactory containerFactory(ApplicationContext parentContext,
                                             WebInterfaceManager webInterfaceManager,
                                             ObjectProvider<List<LingContextCustomizer>> customizersProvider,
                                             List<ResourceGuard> resourceGuards) {
        List<LingContextCustomizer> customizers = customizersProvider.getIfAvailable(Collections::emptyList);
        return new SpringContainerFactory(parentContext, webInterfaceManager, customizers, resourceGuards);
    }

    @Bean
    public TrafficRouter trafficRouter() {
        return new LabelMatchRouter();
    }

    @Bean
    public LingFrameConfig lingFrameConfig(LingFrameProperties properties) {
        LingFrameProperties.RuntimeConfig rtProps = properties.getRuntime();
        LingRuntimeConfig runtimeConfig = LingRuntimeConfig.builder()
                .maxHistorySnapshots(rtProps.getMaxHistorySnapshots())
                .forceCleanupDelaySeconds((int) rtProps.getForceCleanupDelay().getSeconds())
                .dyingCheckIntervalSeconds((int) rtProps.getDyingCheckInterval().getSeconds())
                .defaultTimeoutMs((int) rtProps.getDefaultTimeout().toMillis())
                .bulkheadMaxConcurrent(rtProps.getBulkheadMaxConcurrent())
                .bulkheadAcquireTimeoutMs((int) rtProps.getBulkheadAcquireTimeout().toMillis())
                .rateLimitPerSecond(rtProps.getRateLimitPerSecond())
                .build();

        if (properties.isDevMode()) {
            log.info("LingFrame running in DEV mode");
        }

        LingFrameConfig lingFrameConfig = LingFrameConfig.builder()
                .devMode(properties.isDevMode())
                .autoScan(properties.isAutoScan())
                .lingHome(properties.getLingHome())
                .lingRoots(properties.getLingRoots())
                .runtimeConfig(runtimeConfig)
                .corePoolSize(Runtime.getRuntime().availableProcessors())
                .lingCoreGovernanceEnabled(properties.getLingCoreGovernance().isEnabled())
                .lingCoreGovernanceInternalCalls(properties.getLingCoreGovernance().isGovernInternalCalls())
                .hostCheckPermissions(properties.getLingCoreGovernance().isCheckPermissions())
                .preloadApiJars(properties.getPreloadApiJars())
                .apiOverrideCheckEnabled(properties.isApiOverrideCheckEnabled())
                .build();

        LingFrameConfig.init(lingFrameConfig);

        return lingFrameConfig;
    }

    @Bean
    @ConditionalOnMissingBean
    public LeakDetector leakDetector(EventBus eventBus, LingFrameConfig lingFrameConfig) {
        return new DefaultLeakDetector(eventBus, lingFrameConfig);
    }

    @Bean
    @ConditionalOnMissingBean
    public ResourceGuard resourceGuard() {
        return new SpringBasicResourceGuard();
    }

    @Bean
    public FilterRegistrationBean<?> lingRepeatableReadFilter() {
        Object filter = LingRepeatableReadFilter.createProxy();
        if (filter == null) return null;

        FilterRegistrationBean<?> registration = new FilterRegistrationBean<>();
        try {
            // 动态适配 javax.servlet.Filter 或 jakarta.servlet.Filter
            Class<?> filterInterface = filter.getClass().getInterfaces()[0];
            Method setFilter = registration.getClass().getMethod("setFilter", filterInterface);
            setFilter.invoke(registration, filter);
        } catch (Exception e) {
            log.error("Failed to register LingRepeatableReadFilter: {}", e.getMessage());
        }

        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("lingRepeatableReadFilter");
        return registration;
    }

    @Bean
    public ResourceGuard storageResourceGuard() {
        return new StorageResourceGuard();
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
                                                   List<ResourceGuard> resourceGuards,
                                                   LingResourceManager lingResourceManager,
                                                   LeakDetector leakDetector,
                                                   RuntimeCoordinator runtimeCoordinator,
                                                   ObjectProvider<HotSwapWatcher> hotSwapWatcherProvider) {
        List<LingSecurityVerifier> verifiers = verifiersProvider.getIfAvailable(Collections::emptyList);
        LingUnloadCoordinator unloadCoordinator = new LingUnloadCoordinator(
                pipelineEngine, resourceGuards, lingResourceManager, leakDetector);
        
        return new DefaultLingLifecycleEngine(
                containerFactory, 
                permissionService, 
                lingLoaderFactory, 
                verifiers, 
                eventBus, 
                lingFrameConfig, 
                lingRepository, 
                lingServiceRegistry, 
                pipelineEngine, 
                lingResourceManager, 
                unloadCoordinator, 
                runtimeCoordinator
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public InvocationPipelineEngine invocationPipelineEngine(
            ObjectProvider<FilterRegistry> registryProvider,
            LingRepository lingRepository,
            InvokableMethodCache methodCache,
            PermissionService permissionService,
            ObjectProvider<LingServiceInvoker> invokerProvider,
            ObjectProvider<GovernanceArbitrator> arbitratorProvider,
            ObjectProvider<GovernanceMetricsCollector> governanceMetricsCollectorProvider,
            TrafficRouter trafficRouter,
            EventBus eventBus,
            RuntimeCoordinator runtimeCoordinator) {
        LingServiceInvoker invoker = invokerProvider.getIfAvailable();
        GovernanceArbitrator arbitrator = arbitratorProvider.getIfAvailable();
        GovernanceMetricsCollector governanceMetricsCollector = governanceMetricsCollectorProvider.getIfAvailable();
        FilterRegistry registry = registryProvider
                .getIfAvailable(() -> new FilterRegistry(methodCache, permissionService, invoker, arbitrator));
        // 初始化内置 Filter 并注入依赖（构造器注入）
        registry.initialize(lingRepository, trafficRouter, eventBus, runtimeCoordinator, governanceMetricsCollector);
        // 从灵核 ClassLoader 加载 SPI 扩展
        registry.loadSpiFilters(Thread.currentThread().getContextClassLoader());
        return new InvocationPipelineEngine(registry);
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
    public ServiceExporterListener serviceExporterListener(EventBus eventBus, LingRepository lingRepository,
                                                           LingServiceRegistry lingServiceRegistry,
                                                           ObjectProvider<List<ServiceExporter>> exportersProvider) {
        List<ServiceExporter> exporters = exportersProvider.getIfAvailable(Collections::emptyList);
        return new ServiceExporterListener(eventBus, lingRepository, lingServiceRegistry, exporters);
    }

    @Bean(destroyMethod = "shutdown")
    public SharedApiManager sharedApiManager(LingFrameConfig config) {
        ClassLoader lingCoreCL = Thread.currentThread().getContextClassLoader();
        return new SharedApiManager(lingCoreCL, config);
    }

    @Bean
    public ApplicationRunner lingScannerRunner(
            LingDiscoveryService discoveryService,
            SharedApiManager sharedApiManager) {
        return args -> {
            if (!BOOTSTRAP_DONE.compareAndSet(false, true)) {
                return;
            }

            // ⚠️ 顺序不能反：
            // 1. 先预加载共享契约
            // 2. 再冻结共享边界
            // 3. 最后才允许发现并装载灵元实现
            // 否则不同灵元可能看到不同版本的共享 ABI 视图。
            sharedApiManager.preloadFromConfig();
            sharedApiManager.freezeSharedBoundary();
            discoveryService.scanAndLoad();
        };
    }

    @Bean
    public ApplicationListener<ApplicationReadyEvent> governancePermissionRestoreListener(
            LocalGovernanceRegistry governanceRegistry,
            PermissionService permissionService) {
        return event -> {
            int syncedLingCount = GovernancePermissionSynchronizer.syncAll(governanceRegistry, permissionService);
            if (syncedLingCount > 0) {
                log.info("[Startup] Restored persisted governance permissions for {} ling(s)", syncedLingCount);
            }
        };
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "lingframe", name = "dev-mode", havingValue = "true")
    public HotSwapWatcher hotSwapWatcher(LingLifecycleEngine lifecycleEngine,
                                         LingRepository lingRepository,
                                         EventBus eventBus,
                                         LeakDetector leakDetector) {
        HotSwapWatcher watcher = new HotSwapWatcher(lifecycleEngine, lingRepository, eventBus, leakDetector);
        if (lifecycleEngine instanceof DefaultLingLifecycleEngine) {
            ((DefaultLingLifecycleEngine) lifecycleEngine).setHotSwapWatcher(watcher);
        }
        return watcher;
    }

    @Bean
    public LingContext lingCoreContext(LingRepository lingRepository,
                                       LingServiceRegistry lingServiceRegistry,
                                       InvocationPipelineEngine pipelineEngine,
                                       PermissionService permissionService,
                                       EventBus eventBus) {
        return new DefaultLingContext("lingcore-app", lingRepository, lingServiceRegistry, pipelineEngine,
                permissionService, eventBus);
    }

    @Bean
    public static LingReferenceInjector lingReferenceInjector() {
        return new LingReferenceInjector("lingcore-app");
    }

    @Bean
    public WebInterfaceManager webInterfaceManager(LingRepository lingRepository, TrafficRouter trafficRouter) {
        return new WebInterfaceManager(lingRepository, trafficRouter);
    }

    /**
     * SpringDoc 集成适配 (核心转换器)
     */
    @Configuration
    @ConditionalOnClass(io.swagger.v3.oas.models.OpenAPI.class)
    static class SpringDocIntegrationConfiguration {
        @Bean
        public LingOpenApiCustomizer lingOpenApiCustomizer(WebInterfaceManager webInterfaceManager,
                                                           Environment environment) {
            return new LingOpenApiCustomizer(webInterfaceManager, environment);
        }

        @Bean
        public Object lingSpringDocGlobalCustomizer(LingOpenApiCustomizer lingOpenApiCustomizer) {
            return LingSpringDocCustomizerBridge.createGlobalCustomizer(
                    LingFrameCoreConfiguration.class.getClassLoader(), lingOpenApiCustomizer);
        }

        @Bean
        public static BeanPostProcessor lingSpringDocGroupedOpenApiPostProcessor(LingOpenApiCustomizer lingOpenApiCustomizer) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    LingSpringDocCustomizerBridge.attachToGroupedOpenApi(
                            bean.getClass().getClassLoader(), lingOpenApiCustomizer, bean);
                    return bean;
                }
            };
        }
    }

    /**
     * 🔥 修复：自动初始化 WebInterfaceManager
     * 监听 ApplicationStartedEvent 以确保在 ApplicationRunner (灵元扫描) 执行前完成初始化。
     */
    @Bean
    public ApplicationListener<ApplicationStartedEvent> webInterfaceManagerInitializer(
            WebInterfaceManager webInterfaceManager,
            ObjectProvider<RequestMappingHandlerMapping> mappingProvider,
            ObjectProvider<RequestMappingHandlerAdapter> adapterProvider) {
        return event -> {
            ApplicationContext ctx = event.getApplicationContext();
            if (!(ctx instanceof ConfigurableApplicationContext)) {
                return;
            }

            // ⚠️ 宿主可能由于引入了 Actuator 等库导致存在多个 Mapping 候选，必须精准定位
            RequestMappingHandlerMapping mapping = null;
            try {
                mapping = ctx.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
            } catch (Exception e) {
                mapping = mappingProvider.getIfUnique();
            }

            RequestMappingHandlerAdapter adapter = null;
            try {
                adapter = ctx.getBean("requestMappingHandlerAdapter", RequestMappingHandlerAdapter.class);
            } catch (Exception e) {
                adapter = adapterProvider.getIfUnique();
            }

            if (mapping != null && adapter != null) {
                webInterfaceManager.init(mapping, adapter, (ConfigurableApplicationContext) ctx);
                log.info("🌍 [LingFrame Web] WebInterfaceManager initialized with host Spring MVC components");
            } else {
                log.warn("⚠️ [LingFrame Web] Standard Spring MVC components not found, skipping WebInterfaceManager initialization");
            }
        };
    }

}
