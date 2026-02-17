package com.lingframe.starter.configuration;

import com.lingframe.api.context.PluginContext;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.classloader.DefaultPluginLoaderFactory;
import com.lingframe.core.classloader.SharedApiManager;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.context.CorePluginContext;
import com.lingframe.core.dev.HotSwapWatcher;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.governance.HostGovernanceRule;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.governance.provider.StandardGovernancePolicyProvider;
import com.lingframe.core.invoker.DefaultPluginServiceInvoker;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.loader.PluginDiscoveryService;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.core.plugin.PluginRuntimeConfig;
import com.lingframe.core.router.LabelMatchRouter;
import com.lingframe.core.security.DefaultPermissionService;
import com.lingframe.core.spi.*;
import com.lingframe.infra.cache.configuration.CaffeineWrapperProcessor;
import com.lingframe.infra.cache.configuration.RedisWrapperProcessor;
import com.lingframe.infra.cache.configuration.SpringCacheWrapperProcessor;
import com.lingframe.infra.storage.configuration.DataSourceWrapperProcessor;
import com.lingframe.starter.adapter.SpringContainerFactory;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.processor.HostBeanGovernanceProcessor;
import com.lingframe.starter.processor.LingReferenceInjector;
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
        HostBeanGovernanceProcessor.class
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
    @ConditionalOnMissingBean(PluginLoaderFactory.class)
    public PluginLoaderFactory defaultPluginLoaderFactory() {
        return new DefaultPluginLoaderFactory();
    }

    @Bean
    public StandardGovernancePolicyProvider standardGovernancePolicyProvider(
            LocalGovernanceRegistry registry,
            LingFrameProperties properties) {

        List<HostGovernanceRule> coreRules = new ArrayList<>();
        if (properties.getRules() != null) {
            for (LingFrameProperties.GovernanceRule r : properties.getRules()) {
                coreRules.add(HostGovernanceRule.builder()
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
    public GovernanceKernel governanceKernel(PermissionService permissionService,
            GovernanceArbitrator arbitrator, EventBus eventBus) {
        return new GovernanceKernel(permissionService, arbitrator, eventBus);
    }

    @Bean
    public ContainerFactory containerFactory(ApplicationContext parentContext,
            WebInterfaceManager webInterfaceManager) {
        return new SpringContainerFactory(parentContext, webInterfaceManager);
    }

    @Bean
    public TrafficRouter trafficRouter() {
        return new LabelMatchRouter();
    }

    @Bean
    public PluginServiceInvoker pluginServiceInvoker() {
        return new DefaultPluginServiceInvoker();
    }

    @Bean
    public LingFrameConfig lingFrameConfig(LingFrameProperties properties) {
        LingFrameProperties.Runtime rtProps = properties.getRuntime();
        PluginRuntimeConfig runtimeConfig = PluginRuntimeConfig.builder()
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
                .pluginHome(properties.getPluginHome())
                .pluginRoots(properties.getPluginRoots())
                .runtimeConfig(runtimeConfig)
                .corePoolSize(Runtime.getRuntime().availableProcessors())
                .hostGovernanceEnabled(properties.getHostGovernance().isEnabled())
                .hostGovernanceInternalCalls(properties.getHostGovernance().isGovernInternalCalls())
                .hostCheckPermissions(properties.getHostGovernance().isCheckPermissions())
                .preloadApiJars(properties.getPreloadApiJars())
                .build();

        LingFrameConfig.init(lingFrameConfig);

        return lingFrameConfig;
    }

    @Bean
    public PluginManager pluginManager(ContainerFactory containerFactory,
            PermissionService permissionService,
            GovernanceKernel governanceKernel,
            PluginLoaderFactory pluginLoaderFactory,
            ObjectProvider<List<PluginSecurityVerifier>> verifiersProvider,
            EventBus eventBus,
            TrafficRouter trafficRouter,
            PluginServiceInvoker pluginServiceInvoker,
            ObjectProvider<TransactionVerifier> transactionVerifierProvider,
            ObjectProvider<List<ThreadLocalPropagator>> propagatorsProvider,
            LingFrameConfig lingFrameConfig,
            LocalGovernanceRegistry localGovernanceRegistry,
            ObjectProvider<ResourceGuard> resourceGuardProvider) {

        TransactionVerifier transactionVerifier = transactionVerifierProvider.getIfAvailable();
        List<ThreadLocalPropagator> propagators = propagatorsProvider.getIfAvailable(Collections::emptyList);
        List<PluginSecurityVerifier> verifiers = verifiersProvider.getIfAvailable(Collections::emptyList);
        ResourceGuard resourceGuard = resourceGuardProvider.getIfAvailable();

        return new PluginManager(containerFactory, permissionService, governanceKernel,
                pluginLoaderFactory, verifiers, eventBus, trafficRouter, pluginServiceInvoker,
                transactionVerifier, propagators, lingFrameConfig, localGovernanceRegistry, resourceGuard);
    }

    @Bean
    public PluginDiscoveryService pluginDiscoveryService(LingFrameConfig config, PluginManager pluginManager) {
        return new PluginDiscoveryService(config, pluginManager);
    }

    @Bean
    public SharedApiManager sharedApiManager(LingFrameConfig config) {
        ClassLoader hostCL = Thread.currentThread().getContextClassLoader();
        return new SharedApiManager(hostCL, config);
    }

    @Bean
    public ApplicationRunner pluginScannerRunner(
            PluginDiscoveryService discoveryService,
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
    public HotSwapWatcher hotSwapWatcher(PluginManager pluginManager, EventBus eventBus) {
        return new HotSwapWatcher(pluginManager, eventBus);
    }

    @Bean
    public PluginContext hostPluginContext(PluginManager pluginManager,
            PermissionService permissionService,
            EventBus eventBus) {
        return new CorePluginContext("host-app", pluginManager, permissionService, eventBus);
    }

    @Bean
    public LingReferenceInjector lingReferenceInjector() {
        return new LingReferenceInjector("host-app");
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
