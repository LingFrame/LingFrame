package com.lingframe.starter.configuration;

import com.lingframe.api.context.LingContext;
import com.lingframe.core.resource.BasicResourceGuard;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.classloader.DefaultLingLoaderFactory;
import com.lingframe.core.classloader.SharedApiManager;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.context.CoreLingContext;
import com.lingframe.core.dev.HotSwapWatcher;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.governance.LingCoreGovernanceRule;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.governance.provider.StandardGovernancePolicyProvider;
import com.lingframe.core.ling.DefaultLingLifecycleEngine;
import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.DefaultLingResourceManager;
import com.lingframe.core.ling.DefaultLingServiceRegistry;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingResourceManager;
import com.lingframe.core.ling.LingRuntimeConfig;
import com.lingframe.core.ling.LingServiceRegistry;
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
import com.lingframe.starter.deploy.DefaultLingDeployService;
import com.lingframe.starter.deploy.LingDeployService;
import com.lingframe.starter.event.ServiceExporterListener;
import com.lingframe.starter.processor.LingCoreBeanGovernanceProcessor;
import com.lingframe.starter.processor.LingReferenceInjector;
import com.lingframe.starter.spi.LingContextCustomizer;
import com.lingframe.starter.spi.ServiceExporter;
import com.lingframe.starter.web.WebInterfaceManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LingFrame 公共核心配置 —— 版本无关
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
    public LingResourceManager lingResourceManager(EventBus eventBus, InvokableMethodCache methodCache) {
        return new DefaultLingResourceManager(eventBus, methodCache);
    }

    @Bean
    public ContainerFactory containerFactory(ApplicationContext parentContext,
            WebInterfaceManager webInterfaceManager,
            ObjectProvider<List<LingContextCustomizer>> customizersProvider) {
        List<LingContextCustomizer> customizers = customizersProvider.getIfAvailable(Collections::emptyList);
        return new SpringContainerFactory(parentContext, webInterfaceManager, customizers);
    }

    @Bean
    public TrafficRouter trafficRouter() {
        return new LabelMatchRouter();
    }

    @Bean
    public LingFrameConfig lingFrameConfig(LingFrameProperties properties) {
        LingFrameProperties.Runtime rtProps = properties.getRuntime();
        LingRuntimeConfig runtimeConfig = LingRuntimeConfig.builder()
                .maxHistorySnapshots(rtProps.getMaxHistorySnapshots())
                .forceCleanupDelaySeconds((int) rtProps.getForceCleanupDelay().getSeconds())
                .dyingCheckIntervalSeconds((int) rtProps.getDyingCheckInterval().getSeconds())
                .defaultTimeoutMs((int) rtProps.getDefaultTimeout().toMillis())
                .bulkheadMaxConcurrent(rtProps.getBulkheadMaxConcurrent())
                .bulkheadAcquireTimeoutMs((int) rtProps.getBulkheadAcquireTimeout().toMillis())
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
                .build();

        LingFrameConfig.init(lingFrameConfig);

        return lingFrameConfig;
    }

    @Bean
    @ConditionalOnMissingBean
    public ResourceGuard resourceGuard() {
        return new BasicResourceGuard();
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
            ResourceGuard resourceGuard) {
        List<LingSecurityVerifier> verifiers = verifiersProvider.getIfAvailable(Collections::emptyList);
        return new DefaultLingLifecycleEngine(containerFactory, permissionService,
                lingLoaderFactory, verifiers, eventBus, lingFrameConfig, lingRepository, lingServiceRegistry,
                pipelineEngine, resourceGuard);
    }

    @Bean
    @ConditionalOnMissingBean
    public InvocationPipelineEngine invocationPipelineEngine(
            ObjectProvider<FilterRegistry> registryProvider,
            LingRepository lingRepository,
            InvokableMethodCache methodCache,
            TrafficRouter trafficRouter,
            EventBus eventBus) {
        FilterRegistry registry = registryProvider
                .getIfAvailable(() -> new FilterRegistry(methodCache));
        // 初始化内置 Filter 并注入依赖（构造器注入）
        registry.initialize(lingRepository, trafficRouter, eventBus);
        // 从宿主 ClassLoader 加载 SPI 扩展
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

    @Bean
    public ServiceExporterListener serviceExporterListener(EventBus eventBus, LingRepository lingRepository,
            LingServiceRegistry lingServiceRegistry,
            ObjectProvider<List<ServiceExporter>> exportersProvider) {
        List<ServiceExporter> exporters = exportersProvider.getIfAvailable(Collections::emptyList);
        return new ServiceExporterListener(eventBus, lingRepository, lingServiceRegistry, exporters);
    }

    @Bean
    public SharedApiManager sharedApiManager(LingFrameConfig config) {
        ClassLoader hostCL = Thread.currentThread().getContextClassLoader();
        return new SharedApiManager(hostCL, config);
    }

    @Bean
    public ApplicationRunner lingScannerRunner(
            LingDiscoveryService discoveryService,
            SharedApiManager sharedApiManager) {
        return args -> {
            if (!BOOTSTRAP_DONE.compareAndSet(false, true)) {
                return;
            }
            sharedApiManager.preloadFromConfig();
            discoveryService.scanAndLoad();
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "lingframe", name = "dev-mode", havingValue = "true")
    public HotSwapWatcher hotSwapWatcher(LingLifecycleEngine lifecycleEngine, EventBus eventBus) {
        return new HotSwapWatcher(lifecycleEngine, eventBus);
    }

    @Bean
    public LingContext lingCoreContext(LingRepository lingRepository,
            LingServiceRegistry lingServiceRegistry,
            InvocationPipelineEngine pipelineEngine,
            PermissionService permissionService,
            EventBus eventBus) {
        return new CoreLingContext("lingcore-app", lingRepository, lingServiceRegistry, pipelineEngine,
                permissionService, eventBus);
    }

    @Bean
    public LingReferenceInjector lingReferenceInjector() {
        return new LingReferenceInjector("lingcore-app");
    }

    @Bean
    public WebInterfaceManager webInterfaceManager() {
        return new WebInterfaceManager();
    }

    @Bean
    public ApplicationListener<ContextRefreshedEvent> lingWebInitializer(
            WebInterfaceManager manager,
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping hostMapping,
            RequestMappingHandlerAdapter adapter) {
        return event -> {
            if (event.getApplicationContext().getParent() == null) {
                if (event.getApplicationContext() instanceof ConfigurableApplicationContext) {
                    ConfigurableApplicationContext cac = (ConfigurableApplicationContext) event.getApplicationContext();
                    manager.init(hostMapping, adapter, cac);
                }
            }
        };
    }
}
