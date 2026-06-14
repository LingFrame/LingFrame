package com.lingframe.dashboard.config;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.governance.LocalGovernanceRegistry;

import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.router.LabelMatchRouter;
import com.lingframe.dashboard.converter.LingInfoConverter;
import com.lingframe.core.router.CanaryRouter;
import com.lingframe.dashboard.metrics.LingMetricsMeterBridge;
import com.lingframe.dashboard.scheduler.MetricsCollectorScheduler;
import com.lingframe.dashboard.security.AccessTokenInterceptor;
import com.lingframe.dashboard.security.AccessTokenProperties;
import com.lingframe.dashboard.security.CorsProperties;
import com.lingframe.dashboard.security.ReadOnlyInterceptor;
import com.lingframe.dashboard.security.ReadOnlyProperties;
import com.lingframe.dashboard.service.DashboardService;
import com.lingframe.dashboard.service.LogStreamService;
import com.lingframe.dashboard.service.RuntimeDiagnosticsService;
import com.lingframe.dashboard.service.SimulateService;
import com.lingframe.dashboard.service.ServicePlaygroundService;
import com.lingframe.dashboard.storage.AuditStorage;
import com.lingframe.dashboard.storage.GovernanceConfigRestorer;
import com.lingframe.dashboard.storage.GovernanceStorage;
import com.lingframe.dashboard.storage.MetricsStorage;
import com.lingframe.dashboard.storage.StorageInitializer;
import com.lingframe.dashboard.storage.StorageProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties({StorageProperties.class, AccessTokenProperties.class, ReadOnlyProperties.class, CorsProperties.class})
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
    public DashboardService dashboardService(
            LingFrameConfig lingFrameConfig,
            LingLifecycleEngine lifecycleEngine,
            LingRepository lingRepository,
            LocalGovernanceRegistry governanceRegistry,
            CanaryRouter canaryRouter,
            LingInfoConverter lingInfoConverter,
            PermissionService permissionService,
            RuntimeCoordinator runtimeCoordinator,
            @Autowired(required = false) GovernanceStorage governanceStorage) {
        DashboardService service = new DashboardService(lingFrameConfig, lifecycleEngine, lingRepository, governanceRegistry, canaryRouter,
                lingInfoConverter,
                permissionService,
                runtimeCoordinator);
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
            InvocationPipelineEngine pipelineEngine) {
        return new SimulateService(lingRepository, eventBus, canaryRouter, permissionService, pipelineEngine);
    }

    @Bean
    public ServicePlaygroundService servicePlaygroundService(
            LingServiceRegistry lingServiceRegistry,
            LingRepository lingRepository,
            InvocationPipelineEngine pipelineEngine,
            ObjectMapper objectMapper) {
        return new ServicePlaygroundService(lingServiceRegistry, lingRepository, pipelineEngine, objectMapper);
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

    @Bean("dashboardJdbcTemplate")
    @ConditionalOnProperty(prefix = "lingframe.dashboard.storage", name = "enabled", havingValue = "true", matchIfMissing = true)
    public JdbcTemplate dashboardJdbcTemplate(StorageProperties storageProperties) {
        // 确保数据库文件所在目录存在，否则 SQLite 无法创建数据库文件
        java.io.File dbFile = new java.io.File(storageProperties.getPath());
        java.io.File parentDir = dbFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (parentDir.mkdirs()) {
                log.info("[LingFrame] Created database directory: {}", parentDir.getAbsolutePath());
            } else {
                log.warn("[LingFrame] Failed to create database directory: {}", parentDir.getAbsolutePath());
            }
        }

        org.springframework.jdbc.datasource.DriverManagerDataSource ds = new org.springframework.jdbc.datasource.DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + storageProperties.getPath());
        java.util.Properties props = new java.util.Properties();
        props.setProperty("journal_mode", "WAL");
        props.setProperty("busy_timeout", "5000");
        ds.setConnectionProperties(props);
        return new JdbcTemplate(ds);
    }

    @Bean
    @ConditionalOnBean(name = "dashboardJdbcTemplate")
    public StorageInitializer storageInitializer(JdbcTemplate dashboardJdbcTemplate, StorageProperties storageProperties) {
        return new StorageInitializer(dashboardJdbcTemplate, storageProperties);
    }

    @Bean
    @ConditionalOnBean(name = "dashboardJdbcTemplate")
    public MetricsStorage metricsStorage(JdbcTemplate dashboardJdbcTemplate) {
        return new MetricsStorage(dashboardJdbcTemplate);
    }

    @Bean
    @ConditionalOnBean(name = "dashboardJdbcTemplate")
    public GovernanceStorage governanceStorage(JdbcTemplate dashboardJdbcTemplate, ObjectMapper objectMapper) {
        return new GovernanceStorage(dashboardJdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnBean(name = "dashboardJdbcTemplate")
    public AuditStorage auditStorage(JdbcTemplate dashboardJdbcTemplate) {
        return new AuditStorage(dashboardJdbcTemplate);
    }

    @Bean
    @ConditionalOnBean(GovernanceStorage.class)
    public GovernanceConfigRestorer governanceConfigRestorer(
            GovernanceStorage governanceStorage,
            LocalGovernanceRegistry governanceRegistry,
            CanaryRouter canaryRouter) {
        return new GovernanceConfigRestorer(governanceStorage, governanceRegistry, canaryRouter);
    }

    @Bean
    @ConditionalOnBean(MetricsStorage.class)
    public MetricsCollectorScheduler metricsCollectorScheduler(MetricsStorage metricsStorage, StorageProperties storageProperties) {
        MetricsCollectorScheduler scheduler = new MetricsCollectorScheduler(metricsStorage, storageProperties);
        scheduler.start();
        return scheduler;
    }

    // ==================== AccessToken 安全 ====================

    @Bean
    @ConditionalOnProperty(prefix = "lingframe.dashboard.access-token", name = "enabled", havingValue = "true")
    public AccessTokenInterceptor accessTokenInterceptor(AccessTokenProperties accessTokenProperties) {
        return new AccessTokenInterceptor(accessTokenProperties);
    }

    // ==================== WebMvc 配置 ====================

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
                // 只读模式拦截器（优先级高于 token 拦截器）
                if (readOnlyInterceptor != null) {
                    registry.addInterceptor(readOnlyInterceptor)
                            .addPathPatterns("/lingframe/dashboard/**")
                            .excludePathPatterns(
                                "/lingframe/dashboard/ui/**",
                                "/lingframe/dashboard/stream"
                            );
                }
                // Token 认证拦截器
                if (accessTokenInterceptor != null) {
                    registry.addInterceptor(accessTokenInterceptor)
                            .addPathPatterns(
                                "/lingframe/dashboard/lings/**",
                                "/lingframe/dashboard/governance/**",
                                "/lingframe/dashboard/simulate/**",
                                "/lingframe/dashboard/playground/**",
                                "/lingframe/dashboard/metrics/**",
                                "/lingframe/dashboard/stream-ticket"
                            );
                }
            }
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "lingframe.dashboard.readonly", name = "enabled", havingValue = "true")
    public ReadOnlyInterceptor readOnlyInterceptor(ReadOnlyProperties readOnlyProperties) {
        return new ReadOnlyInterceptor(readOnlyProperties);
    }
}
