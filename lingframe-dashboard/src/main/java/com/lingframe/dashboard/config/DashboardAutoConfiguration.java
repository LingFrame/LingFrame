package com.lingframe.dashboard.config;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.governance.LocalGovernanceRegistry;

import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.router.LabelMatchRouter;
import com.lingframe.dashboard.converter.LingInfoConverter;
import com.lingframe.core.router.CanaryRouter;
import com.lingframe.dashboard.metrics.LingMetricsMeterBridge;
import com.lingframe.dashboard.service.DashboardService;
import com.lingframe.dashboard.service.LogStreamService;
import com.lingframe.dashboard.service.SimulateService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Slf4j
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
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
            RuntimeCoordinator runtimeCoordinator) {
        return new DashboardService(lingFrameConfig, lifecycleEngine, lingRepository, governanceRegistry, canaryRouter,
                lingInfoConverter,
                permissionService,
                runtimeCoordinator);
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
    public LogStreamService logStreamService(EventBus eventBus) {
        return new LogStreamService(eventBus);
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

    @Bean
    public WebMvcConfigurer dashboardWebMvcConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addViewControllers(ViewControllerRegistry registry) {
                registry.addRedirectViewController("/lingframe/dashboard/ui", "/lingframe/dashboard/ui/");
                registry.addViewController("/lingframe/dashboard/ui/").setViewName("forward:/lingframe/dashboard/ui/index.html");
                registry.addViewController("/lingframe/dashboard/ui/{path:[^\\.]*}")
                        .setViewName("forward:/lingframe/dashboard/ui/index.html");
                registry.addViewController("/lingframe/dashboard/ui/**/{path:[^\\.]*}")
                        .setViewName("forward:/lingframe/dashboard/ui/index.html");
            }
        };
    }

}
