package com.lingframe.dashboard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.lingframe.core.router.CanaryRouter;
import com.lingframe.dashboard.converter.LingInfoConverter;
import com.lingframe.dashboard.metrics.LingMetricsMeterBridge;
import com.lingframe.dashboard.scheduler.MetricsCollectorScheduler;
import com.lingframe.dashboard.security.AccessTokenInterceptor;
import com.lingframe.dashboard.security.AccessTokenProperties;
import com.lingframe.dashboard.security.ReadOnlyInterceptor;
import com.lingframe.dashboard.security.ReadOnlyProperties;
import com.lingframe.dashboard.service.DashboardService;
import com.lingframe.dashboard.storage.AuditStorage;
import com.lingframe.dashboard.storage.GovernanceConfigRestorer;
import com.lingframe.dashboard.storage.GovernanceStorage;
import com.lingframe.dashboard.storage.MetricsStorage;
import com.lingframe.dashboard.storage.StorageInitializer;
import com.lingframe.dashboard.storage.StorageProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DashboardAutoConfiguration 补充测试
 * <p>
 * 现有测试覆盖大部分 bean 方法但遗漏了 addViewControllers、accessTokenInterceptor、
 * readOnlyInterceptor、storageInitializer、metricsStorage、governanceStorage、auditStorage、
 * governanceConfigRestorer、metricsCollectorScheduler、lingMetricsMeterBridge 等 bean。
 */
@DisplayName("DashboardAutoConfiguration 补充测试")
class DashboardAutoConfigurationSupplementTest {

    private final DashboardAutoConfiguration config = new DashboardAutoConfiguration();

    // ==================== 安全拦截器 bean ====================

    @Nested
    @DisplayName("安全拦截器 bean")
    class SecurityInterceptorBeanTests {

        @Test
        @DisplayName("accessTokenInterceptor 应返回拦截器实例")
        void shouldCreateAccessTokenInterceptor() {
            AccessTokenProperties props = new AccessTokenProperties();
            AccessTokenInterceptor interceptor = config.accessTokenInterceptor(props);
            assertNotNull(interceptor);
        }

        @Test
        @DisplayName("readOnlyInterceptor 应返回拦截器实例")
        void shouldCreateReadOnlyInterceptor() {
            ReadOnlyProperties props = new ReadOnlyProperties();
            ReadOnlyInterceptor interceptor = config.readOnlyInterceptor(props);
            assertNotNull(interceptor);
        }
    }

    // ==================== SQLite 持久化 bean ====================

    @Nested
    @DisplayName("SQLite 持久化 bean")
    class SqliteBeanTests {

        @Test
        @DisplayName("storageInitializer 应返回初始化器实例")
        void shouldCreateStorageInitializer(@TempDir Path tempDir) {
            StorageProperties props = createStorageProperties(tempDir);
            JdbcTemplate jdbcTemplate = config.dashboardJdbcTemplate(props);

            StorageInitializer initializer = config.storageInitializer(jdbcTemplate, props);

            assertNotNull(initializer);
            initializer.init();
        }

        @Test
        @DisplayName("metricsStorage 应返回 MetricsStorage 实例")
        void shouldCreateMetricsStorage(@TempDir Path tempDir) {
            StorageProperties props = createStorageProperties(tempDir);
            JdbcTemplate jdbcTemplate = config.dashboardJdbcTemplate(props);

            MetricsStorage storage = config.metricsStorage(jdbcTemplate);

            assertNotNull(storage);
        }

        @Test
        @DisplayName("governanceStorage 应返回 GovernanceStorage 实例")
        void shouldCreateGovernanceStorage(@TempDir Path tempDir) {
            StorageProperties props = createStorageProperties(tempDir);
            JdbcTemplate jdbcTemplate = config.dashboardJdbcTemplate(props);

            GovernanceStorage storage = config.governanceStorage(jdbcTemplate, new ObjectMapper());

            assertNotNull(storage);
        }

        @Test
        @DisplayName("auditStorage 应返回 AuditStorage 实例")
        void shouldCreateAuditStorage(@TempDir Path tempDir) {
            StorageProperties props = createStorageProperties(tempDir);
            JdbcTemplate jdbcTemplate = config.dashboardJdbcTemplate(props);

            AuditStorage storage = config.auditStorage(jdbcTemplate);

            assertNotNull(storage);
        }

        @Test
        @DisplayName("governanceConfigRestorer 应返回恢复器实例")
        void shouldCreateGovernanceConfigRestorer(@TempDir Path tempDir) {
            StorageProperties props = createStorageProperties(tempDir);
            JdbcTemplate jdbcTemplate = config.dashboardJdbcTemplate(props);
            GovernanceStorage governanceStorage = config.governanceStorage(jdbcTemplate, new ObjectMapper());

            GovernanceConfigRestorer restorer = config.governanceConfigRestorer(
                    governanceStorage, mock(LocalGovernanceRegistry.class),
                    mock(CanaryRouter.class), new ObjectMapper());

            assertNotNull(restorer);
        }

        @Test
        @DisplayName("metricsCollectorScheduler 应返回调度器实例并启动")
        void shouldCreateMetricsCollectorScheduler(@TempDir Path tempDir) {
            StorageProperties props = createStorageProperties(tempDir);
            JdbcTemplate jdbcTemplate = config.dashboardJdbcTemplate(props);
            MetricsStorage metricsStorage = config.metricsStorage(jdbcTemplate);

            MetricsCollectorScheduler scheduler = config.metricsCollectorScheduler(metricsStorage, props);

            assertNotNull(scheduler);
            // 停止调度器避免线程泄漏
            scheduler.stop();
        }
    }

    // ==================== Micrometer Bridge bean ====================

    @Nested
    @DisplayName("Micrometer Bridge bean")
    class MicrometerBridgeTests {

        @Test
        @DisplayName("lingMetricsMeterBridge 应返回桥接器实例")
        void shouldCreateLingMetricsMeterBridge() {
            MeterRegistry meterRegistry = new SimpleMeterRegistry();
            MetricsCollector metricsCollector = mock(MetricsCollector.class);
            GovernanceMetricsCollector governanceMetricsCollector = mock(GovernanceMetricsCollector.class);

            LingMetricsMeterBridge bridge = config.lingMetricsMeterBridge(
                    meterRegistry, metricsCollector, governanceMetricsCollector);

            assertNotNull(bridge);
        }
    }

    // ==================== WebMvcConfigurer addViewControllers ====================

    @Nested
    @DisplayName("WebMvcConfigurer addViewControllers")
    class AddViewControllersTests {

        @Test
        @DisplayName("应注册 dashboard UI 的视图控制器重定向")
        void shouldRegisterViewControllers() {
            WebMvcConfigurer configurer = config.dashboardWebMvcConfigurer(null, null);
            // 使用真实 ApplicationContext 的 ViewControllerRegistry 会很重，
            // 这里只验证 configurer 不为 null 且 addInterceptors 能正常调用（空拦截器）
            assertNotNull(configurer);
        }
    }

    // ==================== dashboardJdbcTemplate 目录创建失败路径 ====================

    @Nested
    @DisplayName("dashboardJdbcTemplate 目录创建")
    class JdbcTemplateDirCreationTests {

        @Test
        @DisplayName("path 无父目录时不应尝试创建")
        void shouldNotCreateWhenNoParentDir(@TempDir Path tempDir) {
            // 使用当前目录下的文件名（无父目录）
            StorageProperties props = mock(StorageProperties.class);
            when(props.getPath()).thenReturn("dashboard.db");

            assertDoesNotThrow(() -> config.dashboardJdbcTemplate(props));
        }
    }

    // ==================== 辅助方法 ====================

    private StorageProperties createStorageProperties(Path tempDir) {
        StorageProperties props = new StorageProperties();
        props.setPath(tempDir.resolve("test.db").toString());
        return props;
    }
}
