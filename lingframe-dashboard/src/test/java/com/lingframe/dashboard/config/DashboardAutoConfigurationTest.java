package com.lingframe.dashboard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.config.LingFrameInfo;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.governance.GovernanceAdminService;
import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.routing.MigrationStateHolder;
import com.lingframe.core.routing.ProviderWeightRouter;
import com.lingframe.dashboard.converter.LingInfoConverter;
import com.lingframe.dashboard.service.DashboardService;
import com.lingframe.dashboard.service.LeakDetectionCacheService;
import com.lingframe.dashboard.service.LingResourceMetricsCollector;
import com.lingframe.dashboard.service.LogStreamService;
import com.lingframe.dashboard.service.RuntimeDiagnosticsService;
import com.lingframe.dashboard.service.ServicePlaygroundService;
import com.lingframe.dashboard.service.SimulateService;
import com.lingframe.dashboard.security.AccessTokenProperties;
import com.lingframe.dashboard.storage.GovernanceStorage;
import com.lingframe.dashboard.storage.StorageProperties;
import java.io.File;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Dashboard 自动配置单元测试
 * 覆盖：简单 bean 方法返回类型 / dashboardJdbcTemplate 目录创建 / dashboardService 条件注入 /
 *      WebMvc 视图配置（安全拦截器由 dashboard-boot2/boot3 注册）
 */
@DisplayName("Dashboard 自动配置单元测试")
class DashboardAutoConfigurationTest {

    private final DashboardAutoConfiguration config = new DashboardAutoConfiguration();

    @Test
    @DisplayName("lingInfoConverter 应返回转换器实例")
    void shouldCreateLingInfoConverter() {
        assertNotNull(config.lingInfoConverter(
                mock(MetricsCollector.class),
                null,
                null,
                null));
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
                mock(LingRepository.class), mock(EventBus.class),
                mock(PermissionService.class), mock(InvocationPipelineEngine.class),
                mock(LingFrameInfo.class), mock(LingServiceRegistry.class)));
    }

    @Test
    @DisplayName("servicePlaygroundService 应注入各依赖构造实例")
    void shouldCreateServicePlaygroundService() {
        assertNotNull(config.servicePlaygroundService(
                mock(LingServiceRegistry.class), mock(LingRepository.class),
                mock(InvocationPipelineEngine.class), mock(ObjectMapper.class),
                mock(GovernanceArbitrator.class), mock(PermissionService.class),
                mock(ProviderWeightRouter.class)));
    }

    @Test
    @DisplayName("dashboardService 在 governanceStorage 非 null 时应注入（条件分支真）")
    void shouldCreateDashboardServiceWithGovernanceStorage() {
        DashboardService s = config.dashboardService(
                mock(LingFrameConfig.class), mock(LingLifecycleEngine.class), mock(LingRepository.class),
                mock(GovernanceAdminService.class), mock(LingInfoConverter.class),
                mock(PermissionService.class), mock(RuntimeCoordinator.class), mock(ObjectMapper.class),
                new MigrationStateHolder(),
                mock(GovernanceStorage.class));
        assertNotNull(s);
    }

    @Test
    @DisplayName("dashboardService 在 governanceStorage 为 null 时应跳过注入（条件分支假）")
    void shouldCreateDashboardServiceWithoutGovernanceStorage() {
        DashboardService s = config.dashboardService(
                mock(LingFrameConfig.class), mock(LingLifecycleEngine.class), mock(LingRepository.class),
                mock(GovernanceAdminService.class), mock(LingInfoConverter.class),
                mock(PermissionService.class), mock(RuntimeCoordinator.class), mock(ObjectMapper.class),
                new MigrationStateHolder(),
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

    @Test
    @DisplayName("accessTokenInterceptor 条件应 matchIfMissing=true（省略 enabled 时仍注册，与 AccessTokenProperties 默认 fail-closed 一致）")
    void accessTokenInterceptorShouldMatchIfMissing() throws Exception {
        java.lang.reflect.Method method = DashboardAutoConfiguration.class
                .getMethod("accessTokenInterceptor", AccessTokenProperties.class);
        ConditionalOnProperty condition = method.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition, "accessTokenInterceptor 应声明 @ConditionalOnProperty");
        assertTrue(condition.matchIfMissing(),
                "省略 lingframe.dashboard.access-token.enabled 时必须注册拦截器（AccessTokenProperties.enabled 默认 true）");
        assertEquals("lingframe.dashboard.access-token", condition.prefix());
        assertArrayEquals(new String[]{"enabled"}, condition.name());
        assertEquals("true", condition.havingValue());
    }

    @Test
    @DisplayName("配置类应启用调度（否则 4 个 @Scheduled 定时任务全部失效）")
    void shouldEnableScheduling() {
        EnableScheduling enableScheduling = DashboardAutoConfiguration.class.getAnnotation(EnableScheduling.class);
        assertNotNull(enableScheduling,
                "DashboardAutoConfiguration 必须声明 @EnableScheduling，否则 SseTicketController.cleanupExpired / "
                        + "RateLimitFilter.cleanupIdleBuckets / LingResourceMetricsCollector.sample / "
                        + "DatabaseBackupScheduler.backup 永不运行");
    }
}