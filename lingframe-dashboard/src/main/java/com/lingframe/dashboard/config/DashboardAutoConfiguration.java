package com.lingframe.dashboard.config;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.config.LingFrameInfo;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.governance.GovernanceAdminService;

import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.metrics.ProviderMetricsCollector;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.router.LabelMatchRouter;
import com.lingframe.dashboard.converter.LingInfoConverter;
import com.lingframe.core.router.CanaryRouter;
import com.lingframe.core.router.ProviderWeightRouter;
import com.lingframe.dashboard.metrics.LingMetricsMeterBridge;
import com.lingframe.dashboard.scheduler.MetricsCollectorScheduler;
import com.lingframe.dashboard.security.AccessTokenInterceptor;
import com.lingframe.dashboard.security.AccessTokenProperties;
import com.lingframe.dashboard.security.CorsProperties;
import com.lingframe.dashboard.security.RateLimitProperties;
import com.lingframe.dashboard.security.ReadOnlyInterceptor;
import com.lingframe.dashboard.security.ReadOnlyProperties;
import com.lingframe.dashboard.service.CanaryDecisionService;
import com.lingframe.dashboard.service.ContractRoutingService;
import com.lingframe.dashboard.service.DashboardService;
import com.lingframe.dashboard.service.LeakDetectionCacheService;
import com.lingframe.dashboard.service.LingResourceMetricsCollector;
import com.lingframe.dashboard.service.LogStreamService;
import com.lingframe.dashboard.service.MigrationProgressService;
import com.lingframe.dashboard.service.RuntimeDiagnosticsService;
import com.lingframe.dashboard.service.SimulateService;
import com.lingframe.dashboard.service.ServicePlaygroundService;
import com.lingframe.dashboard.storage.AuditStorage;
import com.lingframe.dashboard.storage.DashboardDataSource;
import com.lingframe.dashboard.storage.GovernanceConfigRestorer;
import com.lingframe.dashboard.storage.GovernanceStorage;
import com.lingframe.dashboard.storage.MetricsStorage;
import com.lingframe.dashboard.storage.StorageInitializer;
import com.lingframe.dashboard.storage.StorageProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.Properties;

@Slf4j
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties({StorageProperties.class, AccessTokenProperties.class, ReadOnlyProperties.class, CorsProperties.class, RateLimitProperties.class})
// Filter/Interceptor 实现由矩阵源码集 java-javax / java-jakarta 编译进同一坐标
@ComponentScan(basePackages = {
        "com.lingframe.dashboard.controller",
        "com.lingframe.dashboard.security",
        "com.lingframe.dashboard.storage"
})
public class DashboardAutoConfiguration {

    public DashboardAutoConfiguration() {
        log.info("[LingFrame] Dashboard unit initializing...");
    }

    // ==================== 基础组件 ====================

    @Bean
    public LingInfoConverter lingInfoConverter() {
        return new LingInfoConverter();
    }

    @Bean
    @Primary
    public CanaryRouter canaryRouter() {
        return new CanaryRouter(new LabelMatchRouter());
    }

    // ==================== Service ====================

    @Bean
    public CanaryDecisionService canaryDecisionService(MetricsCollector metricsCollector) {
        return new CanaryDecisionService(metricsCollector);
    }

    @Bean
    public LeakDetectionCacheService leakDetectionCacheService(EventBus eventBus) {
        return new LeakDetectionCacheService(eventBus);
    }

    @Bean
    public LingResourceMetricsCollector lingResourceMetricsCollector(
            LingRepository lingRepository,
            @Value("${lingframe.dashboard.metaspace-estimate-bytes-per-class:10240}") long metaspaceBytesPerClass) {
        return new LingResourceMetricsCollector(lingRepository, metaspaceBytesPerClass);
    }

    @Bean
    public DashboardService dashboardService(
            LingFrameConfig lingFrameConfig,
            LingLifecycleEngine lifecycleEngine,
            LingRepository lingRepository,
            GovernanceAdminService governanceAdmin,
            CanaryRouter canaryRouter,
            LingInfoConverter lingInfoConverter,
            PermissionService permissionService,
            RuntimeCoordinator runtimeCoordinator,
            ObjectMapper objectMapper,
            @Autowired(required = false) GovernanceStorage governanceStorage) {
        DashboardService service = new DashboardService(lingFrameConfig, lifecycleEngine, lingRepository, governanceAdmin, canaryRouter,
                lingInfoConverter,
                permissionService,
                runtimeCoordinator,
                objectMapper);
        // 条件注入 GovernanceStorage（SQLite 启用时才有，禁用时为 null）
        if (governanceStorage != null) {
            service.setGovernanceStorage(governanceStorage);
        }
        return service;
    }

    @Bean
    public SimulateService simulateService(
            LingRepository lingRepository,
            EventBus eventBus,
            CanaryRouter canaryRouter,
            PermissionService permissionService,
            InvocationPipelineEngine pipelineEngine,
            LingFrameInfo lingFrameInfo) {
        return new SimulateService(lingRepository, eventBus, canaryRouter, permissionService, pipelineEngine, lingFrameInfo);
    }

    @Bean
    public ServicePlaygroundService servicePlaygroundService(
            LingServiceRegistry lingServiceRegistry,
            LingRepository lingRepository,
            InvocationPipelineEngine pipelineEngine,
            ObjectMapper objectMapper,
            CanaryRouter canaryRouter,
            GovernanceArbitrator governanceArbitrator,
            PermissionService permissionService) {
        return new ServicePlaygroundService(lingServiceRegistry, lingRepository, pipelineEngine,
                objectMapper, canaryRouter, governanceArbitrator, permissionService);
    }

    @Bean
    public LogStreamService logStreamService(EventBus eventBus) {
        return new LogStreamService(eventBus);
    }

    @Bean
    public RuntimeDiagnosticsService runtimeDiagnosticsService(EventBus eventBus) {
        return new RuntimeDiagnosticsService(eventBus);
    }

    @Bean
    public ContractRoutingService contractRoutingService(
            LingServiceRegistry lingServiceRegistry,
            @Autowired(required = false) ProviderWeightRouter providerWeightRouter) {
        // ProviderWeightRouter 由 LingFrameLifecycleBeansConfiguration 装配；
        // dashboard 独立运行（无 starter 依赖）时 fallback 到新建实例，保证不空指针
        ProviderWeightRouter router = providerWeightRouter != null ? providerWeightRouter : new ProviderWeightRouter();
        return new ContractRoutingService(lingServiceRegistry, router);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProviderMetricsCollector providerMetricsCollector() {
        // ProviderMetricsCollector 由 InvocationPipelineEngine 在每次调用后写入；
        // dashboard 独立运行时由这里提供默认实例，starter 不会重复装配（@ConditionalOnMissingBean）
        return new ProviderMetricsCollector();
    }

    @Bean
    public MigrationProgressService migrationProgressService(
            ProviderMetricsCollector providerMetricsCollector,
            LingServiceRegistry lingServiceRegistry) {
        return new MigrationProgressService(providerMetricsCollector, lingServiceRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public MetricsCollector metricsCollector(LingRepository lingRepository) {
        return new MetricsCollector(lingRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public GovernanceMetricsCollector governanceMetricsCollector() {
        return new GovernanceMetricsCollector();
    }

    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    public LingMetricsMeterBridge lingMetricsMeterBridge(
            MeterRegistry meterRegistry,
            MetricsCollector metricsCollector,
            GovernanceMetricsCollector governanceMetricsCollector) {
        return new LingMetricsMeterBridge(meterRegistry, metricsCollector, governanceMetricsCollector);
    }

    // ==================== SQLite 持久化（条件注册） ====================

    @Bean("dashboardDataSource")
    @ConditionalOnProperty(prefix = "lingframe.dashboard.storage", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DashboardDataSource dashboardDataSource(StorageProperties storageProperties) {
        // 确保数据库文件所在目录存在，否则 SQLite 无法创建数据库文件
        File dbFile = new File(storageProperties.getPath());
        File parentDir = dbFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (parentDir.mkdirs()) {
                log.info("[LingFrame] Created database directory: {}", parentDir.getAbsolutePath());
            } else {
                log.warn("[LingFrame] Failed to create database directory: {}", parentDir.getAbsolutePath());
            }
        }

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + storageProperties.getPath());
        Properties props = new Properties();
        props.setProperty("journal_mode", "WAL");
        props.setProperty("busy_timeout", "5000");
        ds.setConnectionProperties(props);
        return new DashboardDataSource(ds);
    }

    // 辅助方法，保留给既有单元测试或外部辅助调用，不再标记为 @Bean
    public JdbcTemplate dashboardJdbcTemplate(StorageProperties storageProperties) {
        DashboardDataSource dds = dashboardDataSource(storageProperties);
        return new JdbcTemplate(dds.getDataSource());
    }

    @Bean
    @ConditionalOnBean(name = "dashboardDataSource")
    public StorageInitializer storageInitializer(DashboardDataSource dashboardDataSource, StorageProperties storageProperties) {
        return new StorageInitializer(new JdbcTemplate(dashboardDataSource.getDataSource()), storageProperties);
    }

    @Bean
    @ConditionalOnBean(name = "dashboardDataSource")
    public MetricsStorage metricsStorage(DashboardDataSource dashboardDataSource) {
        return new MetricsStorage(new JdbcTemplate(dashboardDataSource.getDataSource()));
    }

    @Bean
    @ConditionalOnBean(name = "dashboardDataSource")
    public GovernanceStorage governanceStorage(DashboardDataSource dashboardDataSource, ObjectMapper objectMapper) {
        return new GovernanceStorage(new JdbcTemplate(dashboardDataSource.getDataSource()), objectMapper);
    }

    @Bean
    @ConditionalOnBean(name = "dashboardDataSource")
    public AuditStorage auditStorage(DashboardDataSource dashboardDataSource) {
        return new AuditStorage(new JdbcTemplate(dashboardDataSource.getDataSource()));
    }

    @Bean
    @ConditionalOnBean(GovernanceStorage.class)
    public GovernanceConfigRestorer governanceConfigRestorer(
            GovernanceStorage governanceStorage,
            GovernanceAdminService governanceAdmin,
            CanaryRouter canaryRouter,
            ObjectMapper objectMapper) {
        return new GovernanceConfigRestorer(governanceStorage, governanceAdmin, canaryRouter, objectMapper);
    }

    @Bean
    @ConditionalOnBean(MetricsStorage.class)
    public MetricsCollectorScheduler metricsCollectorScheduler(MetricsStorage metricsStorage, StorageProperties storageProperties) {
        MetricsCollectorScheduler scheduler = new MetricsCollectorScheduler(metricsStorage, storageProperties);
        scheduler.start();
        return scheduler;
    }

    // ==================== AccessToken / 只读 安全 ====================

    @Bean
    @ConditionalOnProperty(prefix = "lingframe.dashboard.access-token", name = "enabled", havingValue = "true")
    public AccessTokenInterceptor accessTokenInterceptor(AccessTokenProperties accessTokenProperties) {
        return new AccessTokenInterceptor(accessTokenProperties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "lingframe.dashboard.readonly", name = "enabled", havingValue = "true")
    public ReadOnlyInterceptor readOnlyInterceptor(ReadOnlyProperties readOnlyProperties) {
        return new ReadOnlyInterceptor(readOnlyProperties);
    }

    // ==================== WebMvc ====================

    @Bean
    public WebMvcConfigurer dashboardWebMvcConfigurer(
            @Autowired(required = false) AccessTokenInterceptor accessTokenInterceptor,
            @Autowired(required = false) ReadOnlyInterceptor readOnlyInterceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addViewControllers(ViewControllerRegistry registry) {
                registry.addRedirectViewController("/lingframe/dashboard/ui", "/lingframe/dashboard/ui/");
                registry.addViewController("/lingframe/dashboard/ui/").setViewName("forward:/dashboard.html");
                registry.addViewController("/lingframe/dashboard/ui/{path:[^\\.]*}")
                        .setViewName("forward:/dashboard.html");
                registry.addViewController("/lingframe/dashboard/ui/**/{path:[^\\.]*}")
                        .setViewName("forward:/dashboard.html");
            }

            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                if (readOnlyInterceptor != null) {
                    registry.addInterceptor(readOnlyInterceptor)
                            .addPathPatterns("/lingframe/dashboard/**")
                            .excludePathPatterns(
                                    "/lingframe/dashboard/ui/**",
                                    "/lingframe/dashboard/stream");
                }
                if (accessTokenInterceptor != null) {
                    registry.addInterceptor(accessTokenInterceptor)
                            .addPathPatterns(
                                    "/lingframe/dashboard/lings/**",
                                    "/lingframe/dashboard/governance/**",
                                    "/lingframe/dashboard/simulate/**",
                                    "/lingframe/dashboard/playground/**",
                                    "/lingframe/dashboard/metrics/**",
                                    "/lingframe/dashboard/packages/**",
                                    "/lingframe/dashboard/contract-routing/**",
                                    "/lingframe/dashboard/migration/**",
                                    "/lingframe/dashboard/stream-ticket");
                }
            }
        };
    }
}
