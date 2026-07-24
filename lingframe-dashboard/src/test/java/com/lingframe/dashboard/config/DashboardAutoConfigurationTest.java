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
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.router.CanaryRouter;
import com.lingframe.dashboard.converter.LingInfoConverter;
import com.lingframe.dashboard.storage.GovernanceStorage;
import com.lingframe.dashboard.service.CanaryDecisionService;
import com.lingframe.dashboard.service.DashboardService;
import com.lingframe.dashboard.service.LeakDetectionCacheService;
import com.lingframe.dashboard.service.LingResourceMetricsCollector;
import com.lingframe.dashboard.service.LogStreamService;
import com.lingframe.dashboard.service.RuntimeDiagnosticsService;
import com.lingframe.dashboard.service.SimulateService;
import com.lingframe.dashboard.service.ServicePlaygroundService;
import com.lingframe.dashboard.storage.StorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dashboard 自动配置单元测试
 * 覆盖：简单 bean 方法返回类型 / dashboardJdbcTemplate 目录创建 / dashboardService 条件注入 /
 *      WebMvc 视图配置（安全拦截器由 dashboard-boot2/boot3 注册）
 */
class DashboardAutoConfigurationTest {

    private final DashboardAutoConfiguration config = new DashboardAutoConfiguration();

    @Test
    @DisplayName("lingInfoConverter 应返回转换器实例")
    void shouldCreateLingInfoConverter() {
        assertNotNull(config.lingInfoConverter());
    }

    @Test
    @DisplayName("canaryRouter 应返回 CanaryRouter 实例（@Primary）")
    void shouldCreateCanaryRouter() {
        CanaryRouter router = config.canaryRouter();
        assertNotNull(router);
    }

    @Test
    @DisplayName("governanceMetricsCollector 应返回实例")
    void shouldCreateGovernanceMetricsCollector() {
        assertNotNull(config.governanceMetricsCollector());
    }

    @Test
    @DisplayName("logStreamService 应注入 EventBus 构造实例")
    void shouldCreateLogStreamService() {
        assertNotNull(config.logStreamService(mock(EventBus.class)));
    }

    @Test
    @DisplayName("runtimeDiagnosticsService 应注入 EventBus 构造实例")
    void shouldCreateRuntimeDiagnosticsService() {
        assertNotNull(config.runtimeDiagnosticsService(mock(EventBus.class)));
    }

    @Test
    @DisplayName("leakDetectionCacheService 应注入 EventBus 构造实例")
    void shouldCreateLeakDetectionCacheService() {
        assertNotNull(config.leakDetectionCacheService(mock(EventBus.class)));
    }

    @Test
    @DisplayName("canaryDecisionService 应注入 MetricsCollector 构造实例")
    void shouldCreateCanaryDecisionService() {
        assertNotNull(config.canaryDecisionService(mock(MetricsCollector.class)));
    }

    @Test
    @DisplayName("metricsCollector 应注入 LingRepository 构造实例（@ConditionalOnMissingBean）")
    void shouldCreateMetricsCollector() {
        assertNotNull(config.metricsCollector(mock(LingRepository.class)));
    }

    @Test
    @DisplayName("lingResourceMetricsCollector 应注入 repository 与 metaspace 估算值")
    void shouldCreateLingResourceMetricsCollector() {
        LingResourceMetricsCollector c = config.lingResourceMetricsCollector(mock(LingRepository.class), 10240L);
        assertNotNull(c);
    }

    @Test
    @DisplayName("simulateService 应注入各依赖构造实例")
    void shouldCreateSimulateService() {
        assertNotNull(config.simulateService(
                mock(LingRepository.class), mock(EventBus.class), mock(CanaryRouter.class),
                mock(PermissionService.class), mock(InvocationPipelineEngine.class),
                mock(LingFrameInfo.class)));
    }

    @Test
    @DisplayName("servicePlaygroundService 应注入各依赖构造实例")
    void shouldCreateServicePlaygroundService() {
        assertNotNull(config.servicePlaygroundService(
                mock(LingServiceRegistry.class), mock(LingRepository.class),
                mock(InvocationPipelineEngine.class), mock(ObjectMapper.class), mock(CanaryRouter.class),
                mock(GovernanceArbitrator.class), mock(PermissionService.class)));
    }

    @Test
    @DisplayName("dashboardService 在 governanceStorage 非 null 时应注入（条件分支真）")
    void shouldCreateDashboardServiceWithGovernanceStorage() {
        DashboardService s = config.dashboardService(
                mock(LingFrameConfig.class), mock(LingLifecycleEngine.class), mock(LingRepository.class),
                mock(GovernanceAdminService.class), mock(CanaryRouter.class), mock(LingInfoConverter.class),
                mock(PermissionService.class), mock(RuntimeCoordinator.class), mock(ObjectMapper.class),
                mock(GovernanceStorage.class));
        assertNotNull(s);
    }

    @Test
    @DisplayName("dashboardService 在 governanceStorage 为 null 时应跳过注入（条件分支假）")
    void shouldCreateDashboardServiceWithoutGovernanceStorage() {
        DashboardService s = config.dashboardService(
                mock(LingFrameConfig.class), mock(LingLifecycleEngine.class), mock(LingRepository.class),
                mock(GovernanceAdminService.class), mock(CanaryRouter.class), mock(LingInfoConverter.class),
                mock(PermissionService.class), mock(RuntimeCoordinator.class), mock(ObjectMapper.class),
                null);
        assertNotNull(s);
    }

    @Test
    @DisplayName("dashboardJdbcTemplate 应创建数据库文件所在目录（若不存在）")
    void shouldCreateDbDirectoryIfMissing(@TempDir Path tempDir) {
        StorageProperties props = mock(StorageProperties.class);
        String dbPath = tempDir.resolve("nested").resolve("deep").resolve("dashboard.db").toString();
        when(props.getPath()).thenReturn(dbPath);

        config.dashboardJdbcTemplate(props);

        File parentDir = tempDir.resolve("nested").resolve("deep").toFile();
        assertTrue(parentDir.exists(), "父目录应被自动创建");
    }

    @Test
    @DisplayName("dashboardJdbcTemplate 在目录已存在时不应抛异常")
    void shouldNotFailWhenDirectoryExists(@TempDir Path tempDir) {
        StorageProperties props = mock(StorageProperties.class);
        String dbPath = tempDir.resolve("dashboard.db").toString();
        when(props.getPath()).thenReturn(dbPath);

        config.dashboardJdbcTemplate(props);
    }

    @Test
    @DisplayName("WebMvcConfigurer 应提供视图转发与拦截器装配入口")
    void shouldCreateWebMvcConfigurer() {
        WebMvcConfigurer configurer = config.dashboardWebMvcConfigurer(null, null);
        assertNotNull(configurer);
    }
}
