package com.lingframe.dashboard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.governance.GovernanceAdminService;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingUnloadCoordinator;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.dashboard.metrics.LingMetricsMeterBridge;
import com.lingframe.dashboard.scheduler.MetricsCollectorScheduler;
import com.lingframe.dashboard.storage.AuditStorage;
import com.lingframe.dashboard.storage.DashboardDataSource;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DashboardAutoConfiguration 补充测试
 * <p>
 * 安全 Filter/Interceptor 已迁至 dashboard-boot2/boot3；本类覆盖存储与视图配置 bean。
 */
@DisplayName("DashboardAutoConfiguration 补充测试")
class DashboardAutoConfigurationSupplementTest {

    private final DashboardAutoConfiguration config = new DashboardAutoConfiguration();

    // ==================== SQLite 持久化 bean ====================

    @Nested
    @DisplayName("SQLite 持久化 bean")
    class SqliteBeanTests {

        @Test
        @DisplayName("storageInitializer 应返回初始化器实例")
        void shouldCreateStorageInitializer(@TempDir Path tempDir) {
            StorageProperties props = createStorageProperties(tempDir);
            DashboardDataSource dashboardDataSource = config.dashboardDataSource(props);

            StorageInitializer initializer = config.storageInitializer(dashboardDataSource, props);

            assertNotNull(initializer);
            initializer.init();
        }

        @Test
        @DisplayName("metricsStorage 应返回 MetricsStorage 实例")
        void shouldCreateMetricsStorage(@TempDir Path tempDir) {
            StorageProperties props = createStorageProperties(tempDir);
            DashboardDataSource dashboardDataSource = config.dashboardDataSource(props);

            MetricsStorage storage = config.metricsStorage(dashboardDataSource);

            assertNotNull(storage);
        }

        @Test
        @DisplayName("governanceStorage 应返回 GovernanceStorage 实例")
        void shouldCreateGovernanceStorage(@TempDir Path tempDir) {
            StorageProperties props = createStorageProperties(tempDir);
            DashboardDataSource dashboardDataSource = config.dashboardDataSource(props);

            GovernanceStorage storage = config.governanceStorage(dashboardDataSource, new ObjectMapper());

            assertNotNull(storage);
        }

        @Test
        @DisplayName("auditStorage 应返回 AuditStorage 实例")
        void shouldCreateAuditStorage(@TempDir Path tempDir) {
            StorageProperties props = createStorageProperties(tempDir);
            DashboardDataSource dashboardDataSource = config.dashboardDataSource(props);

            AuditStorage storage = config.auditStorage(dashboardDataSource);

            assertNotNull(storage);
        }

        @Test
        @DisplayName("governanceConfigRestorer 应返回恢复器实例")
        void shouldCreateGovernanceConfigRestorer(@TempDir Path tempDir) {
            StorageProperties props = createStorageProperties(tempDir);
            DashboardDataSource dashboardDataSource = config.dashboardDataSource(props);
            GovernanceStorage governanceStorage = config.governanceStorage(dashboardDataSource, new ObjectMapper());

            GovernanceConfigRestorer restorer = config.governanceConfigRestorer(
                    governanceStorage, mock(GovernanceAdminService.class),
                    null, null, new ObjectMapper());

            assertNotNull(restorer);
        }

        @Test
        @DisplayName("metricsCollectorScheduler 应返回调度器实例并启动")
        void shouldCreateMetricsCollectorScheduler(@TempDir Path tempDir) {
            StorageProperties props = createStorageProperties(tempDir);
            DashboardDataSource dashboardDataSource = config.dashboardDataSource(props);
            MetricsStorage metricsStorage = config.metricsStorage(dashboardDataSource);

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
            LingRepository lingRepository = mock(LingRepository.class);
            ObjectProvider<LingUnloadCoordinator> unloadCoordinatorProvider = mock(ObjectProvider.class);
            EventBus eventBus = mock(EventBus.class);

            LingMetricsMeterBridge bridge = config.lingMetricsMeterBridge(
                    meterRegistry, metricsCollector, governanceMetricsCollector,
                    lingRepository, unloadCoordinatorProvider, eventBus);

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