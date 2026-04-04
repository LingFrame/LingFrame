package com.lingframe.starter.configuration;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.classloader.DefaultLingLoaderFactory;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.governance.GovernancePermissionSynchronizer;
import com.lingframe.core.governance.LingCoreGovernanceRule;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.governance.provider.StandardGovernancePolicyProvider;
import com.lingframe.core.ling.DefaultLingRepository;
import com.lingframe.core.ling.DefaultLingServiceRegistry;
import com.lingframe.core.ling.InvokableMethodCache;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.ling.LingRuntimeConfig;
import com.lingframe.core.router.LabelMatchRouter;
import com.lingframe.core.security.DefaultPermissionService;
import com.lingframe.core.spi.GovernancePolicyProvider;
import com.lingframe.core.spi.LingLoaderFactory;
import com.lingframe.core.spi.TrafficRouter;
import com.lingframe.infra.cache.configuration.CaffeineWrapperProcessor;
import com.lingframe.infra.cache.configuration.RedisWrapperProcessor;
import com.lingframe.infra.cache.configuration.SpringCacheWrapperProcessor;
import com.lingframe.infra.storage.configuration.DataSourceWrapperProcessor;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.governance.EntryInvocationGovernanceResolver;
import com.lingframe.starter.processor.LingCoreBeanGovernanceProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;

/**
 * 灵珑公共核心配置入口，聚合运行时、生命周期与 Web 装配切片。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(LingFrameProperties.class)
@Import({
        DataSourceWrapperProcessor.class,
        SpringCacheWrapperProcessor.class,
        CaffeineWrapperProcessor.class,
        RedisWrapperProcessor.class,
        LingCoreBeanGovernanceProcessor.class,
        LingFrameRuntimeBeansConfiguration.class,
        LingFrameLifecycleBeansConfiguration.class,
        LingFrameWebSupportConfiguration.class
})
public class LingFrameCoreConfiguration {

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
            for (LingFrameProperties.GovernanceRule rule : properties.getRules()) {
                coreRules.add(LingCoreGovernanceRule.builder()
                        .pattern(rule.getPattern())
                        .permission(rule.getPermission())
                        .accessType(rule.getAccess())
                        .auditEnabled(rule.getAudit())
                        .auditAction(rule.getAuditAction())
                        .timeout(rule.getTimeout())
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
    public EntryInvocationGovernanceResolver entryInvocationGovernanceResolver(
            LingRepository lingRepository,
            LocalGovernanceRegistry governanceRegistry) {
        return new EntryInvocationGovernanceResolver(lingRepository, governanceRegistry);
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
    public TrafficRouter trafficRouter() {
        return new LabelMatchRouter();
    }

    @Bean
    public LingFrameConfig lingFrameConfig(LingFrameProperties properties) {
        LingFrameProperties.RuntimeConfig runtimeProperties = properties.getRuntime();
        LingRuntimeConfig runtimeConfig = LingRuntimeConfig.builder()
                .maxHistorySnapshots(runtimeProperties.getMaxHistorySnapshots())
                .forceCleanupDelaySeconds((int) runtimeProperties.getForceCleanupDelay().getSeconds())
                .dyingCheckIntervalSeconds((int) runtimeProperties.getDyingCheckInterval().getSeconds())
                .defaultTimeoutMs((int) runtimeProperties.getDefaultTimeout().toMillis())
                .bulkheadMaxConcurrent(runtimeProperties.getBulkheadMaxConcurrent())
                .bulkheadAcquireTimeoutMs((int) runtimeProperties.getBulkheadAcquireTimeout().toMillis())
                .rateLimitPerSecond(runtimeProperties.getRateLimitPerSecond())
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
}
