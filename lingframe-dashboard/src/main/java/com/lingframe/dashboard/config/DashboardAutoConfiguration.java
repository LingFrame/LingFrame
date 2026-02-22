package com.lingframe.dashboard.config;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.ling.LingManager;
import com.lingframe.core.router.LabelMatchRouter;
import com.lingframe.dashboard.converter.LingInfoConverter;
import com.lingframe.dashboard.router.CanaryRouter;
import com.lingframe.dashboard.service.DashboardService;
import com.lingframe.dashboard.service.LogStreamService;
import com.lingframe.dashboard.service.SimulateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@Slf4j
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnBean(LingManager.class)
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
            LingManager lingManager,
            LocalGovernanceRegistry governanceRegistry,
            CanaryRouter canaryRouter,
            LingInfoConverter lingInfoConverter,
            PermissionService permissionService) {
        return new DashboardService(lingManager, governanceRegistry, canaryRouter, lingInfoConverter,
                permissionService);
    }

    @Bean
    public SimulateService simulateService(
            LingManager lingManager,
            GovernanceKernel governanceKernel,
            EventBus eventBus,
            PermissionService permissionService) {
        return new SimulateService(lingManager, governanceKernel, eventBus, permissionService);
    }

    @Bean
    public LogStreamService logStreamService(EventBus eventBus) {
        return new LogStreamService(eventBus);
    }

}