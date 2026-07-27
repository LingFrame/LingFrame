package com.lingframe.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.api.exception.LingNotFoundException;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.exception.LingInstallException;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.fsm.TransitionRecord;
import com.lingframe.core.governance.GovernanceAdminService;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.InstancePool;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingUninstallResult;
// CanaryRouter 已删除，路由层去身份化
import com.lingframe.dashboard.converter.LingInfoConverter;
import com.lingframe.dashboard.dto.InvocationGovernanceDTO;
import com.lingframe.dashboard.dto.LingInfoDTO;
import com.lingframe.dashboard.dto.LingUninstallResultDTO;
import com.lingframe.dashboard.dto.TrafficStatsDTO;
import com.lingframe.dashboard.dto.TransitionHistoryDTO;
import com.lingframe.dashboard.storage.GovernanceStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DashboardService 补充测试
 * <p>
 * 覆盖 DashboardServiceTest 未触及的查询、状态切换异常路径、灰度配置持久化、
 * 流量统计、转换历史、包扫描与部署、存储注入等逻辑分支。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardService 补充测试")
class DashboardServiceSupplementTest {

    // 测试类共享 ObjectMapper 单例，避免每个测试方法都 new 一个实例
    private static final ObjectMapper SHARED_OBJECT_MAPPER = new ObjectMapper();

    @Mock
    LingFrameConfig lingFrameConfig;
    @Mock
    LingLifecycleEngine lifecycleEngine;
    @Mock
    LingRepository lingRepository;
    @Mock
    GovernanceAdminService governanceAdmin;
    @Mock
    LingInfoConverter lingInfoConverter;
    @Mock
    PermissionService permissionService;
    @Mock
    RuntimeCoordinator runtimeCoordinator;

    private DashboardService newService() {
        return new DashboardService(lingFrameConfig, lifecycleEngine, lingRepository,
                governanceAdmin, lingInfoConverter, permissionService,
                runtimeCoordinator, null, SHARED_OBJECT_MAPPER);
    }

    // ==================== 查询接口 ====================

    @Nested
    @DisplayName("灵元查询")
    class QueryTests {

        @Test
        @DisplayName("getAllLingInfos 应过滤 null runtime 并转换为 DTO")
        void getAllLingInfosShouldFilterNullAndConvert() {
            DashboardService service = newService();
            LingRuntime runtime = mock(LingRuntime.class);
            LingInfoDTO dto = new LingInfoDTO();
            when(lingRepository.getAllRuntimes()).thenReturn(Arrays.asList(runtime, null));
            lenient().when(runtime.getLingId()).thenReturn("ling1");
            // getEffectivePolicy 内部会再次调用 getRuntime，mock 默认返回 null → 静态策略为 null
            lenient().when(lingRepository.getRuntime("ling1")).thenReturn(null);
            lenient().when(governanceAdmin.getPatchForUpdate("ling1")).thenReturn(new GovernancePolicy());
            when(lingInfoConverter.toDTO(eq(runtime), eq(permissionService), any()))
                    .thenReturn(dto);

            List<LingInfoDTO> result = service.getAllLingInfos();

            assertEquals(1, result.size());
            assertEquals(dto, result.get(0));
        }

        @Test
        @DisplayName("getLingInfo 在 runtime 存在时应返回转换后的 DTO")
        void getLingInfoShouldReturnDtoWhenRuntimeExists() {
            DashboardService service = newService();
            LingRuntime runtime = mock(LingRuntime.class);
            InstancePool pool = mock(InstancePool.class);
            LingInfoDTO dto = new LingInfoDTO();
            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            // getStaticPolicy 会链式访问 getInstancePool().getDefault()，需 mock 避免 NPE
            lenient().when(runtime.getInstancePool()).thenReturn(pool);
            lenient().when(pool.getDefault()).thenReturn(null);
            lenient().when(governanceAdmin.getPatchForUpdate("ling1")).thenReturn(new GovernancePolicy());
            lenient().when(governanceAdmin.getEffectivePolicy("ling1")).thenReturn(null);
            when(lingInfoConverter.toDTO(eq(runtime), eq(permissionService), any()))
                    .thenReturn(dto);

            LingInfoDTO result = service.getLingInfo("ling1");

            assertEquals(dto, result);
        }

        @Test
        @DisplayName("getLingInfo 在 runtime 不存在时应返回 null")
        void getLingInfoShouldReturnNullWhenRuntimeMissing() {
            DashboardService service = newService();
            when(lingRepository.getRuntime("ling1")).thenReturn(null);

            LingInfoDTO result = service.getLingInfo("ling1");

            assertNull(result);
        }

        @Test
        @DisplayName("getLifecycleEvents 应委托给事件存储并返回非 null 列表")
        void getLifecycleEventsShouldDelegateToStore() {
            DashboardService service = newService();
            // 无历史事件时应返回空列表而非 null
            List<DashboardService.LifecycleEvent> result = service.getLifecycleEvents("ling1");
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("getInvocationGovernance 在无策略时应返回字段全 null 的 DTO")
        void getInvocationGovernanceShouldReturnDtoWithNullsWhenNoPolicy() {
            DashboardService service = newService();
            lenient().when(lingRepository.getRuntime("ling1")).thenReturn(null);
            lenient().when(governanceAdmin.getPatchForUpdate("ling1")).thenReturn(new GovernancePolicy());
            lenient().when(governanceAdmin.getEffectivePolicy("ling1")).thenReturn(null);

            InvocationGovernanceDTO result = service.getInvocationGovernance("ling1");

            assertNotNull(result);
            assertNull(result.getTimeoutMs());
            assertNull(result.getRateLimitPerSecond());
        }
    }

    // ==================== 状态切换异常路径 ====================

    @Nested
    @DisplayName("状态切换")
    class StatusUpdateTests {

        @Test
        @DisplayName("updateStatus 在 runtime 不存在时应抛 LingNotFoundException")
        void updateStatusShouldThrowWhenRuntimeMissing() {
            DashboardService service = newService();
            when(lingRepository.getRuntime("ling1")).thenReturn(null);

            assertThrows(LingNotFoundException.class,
                    () -> service.updateStatus("ling1", RuntimeStatus.ACTIVE, "1.0.0"));
        }

        @Test
        @DisplayName("updateStatus 在目标状态不被支持时应抛 InvalidArgumentException")
        void updateStatusShouldThrowInvalidWhenTargetUnsupported() {
            DashboardService service = newService();
            LingRuntime runtime = mock(LingRuntime.class);
            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(runtime.currentStatus()).thenReturn(RuntimeStatus.ACTIVE);
            // DEGRADED 不在 statusCoordinator 的 switch 中，会命中 default 抛 IllegalArgumentException

            assertThrows(InvalidArgumentException.class,
                    () -> service.updateStatus("ling1", RuntimeStatus.DEGRADED, "1.0.0"));
        }
    }

    // ==================== 流量统计 ====================

    @Nested
    @DisplayName("流量统计")
    class TrafficStatsTests {

        @Test
        @DisplayName("getTrafficStats 在 runtime 不存在时应抛 LingNotFoundException")
        void getTrafficStatsShouldThrowWhenRuntimeMissing() {
            DashboardService service = newService();
            when(lingRepository.getRuntime("ling1")).thenReturn(null);

            assertThrows(LingNotFoundException.class,
                    () -> service.getTrafficStats("ling1"));
        }

        @Test
        @DisplayName("getTrafficStats 在 runtime 存在时应返回转换后的 DTO")
        void getTrafficStatsShouldReturnDto() {
            DashboardService service = newService();
            LingRuntime runtime = mock(LingRuntime.class);
            TrafficStatsDTO dto = TrafficStatsDTO.builder().lingId("ling1").build();
            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);
            when(lingInfoConverter.toTrafficStats(runtime)).thenReturn(dto);

            TrafficStatsDTO result = service.getTrafficStats("ling1");

            assertEquals(dto, result);
        }

        @Test
        @DisplayName("resetTrafficStats 在 runtime 不存在时应抛 LingNotFoundException")
        void resetTrafficStatsShouldThrowWhenRuntimeMissing() {
            DashboardService service = newService();
            when(lingRepository.getRuntime("ling1")).thenReturn(null);

            assertThrows(LingNotFoundException.class,
                    () -> service.resetTrafficStats("ling1"));
        }

        @Test
        @DisplayName("resetTrafficStats 在 runtime 存在时不抛异常（流量统计已下沉到 MetricsCollector）")
        void resetTrafficStatsShouldNotThrowWhenRuntimeExists() {
            // 流量统计已从 LingRuntime 下沉到 ProviderMetricsCollector / LingHealthMetrics
            // resetTrafficStats 保留为 Dashboard 兼容入口，不再调 runtime.resetTrafficStats
            DashboardService service = newService();
            LingRuntime runtime = mock(LingRuntime.class);
            when(lingRepository.getRuntime("ling1")).thenReturn(runtime);

            assertDoesNotThrow(() -> service.resetTrafficStats("ling1"));
        }
    }

    // ==================== 转换历史 ====================

    @Nested
    @DisplayName("转换历史")
    class TransitionHistoryTests {

        @Test
        @DisplayName("getTransitionHistory 在状态为 null 时应返回空列表")
        void shouldReturnEmptyWhenStatusNull() {
            DashboardService service = newService();
            when(runtimeCoordinator.getStatus("ling1")).thenReturn(null);

            List<TransitionHistoryDTO> result = service.getTransitionHistory("ling1");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("getTransitionHistory 在无历史记录时应返回空列表")
        void shouldReturnEmptyWhenMachineNull() {
            DashboardService service = newService();
            when(runtimeCoordinator.getStatus("ling1")).thenReturn(RuntimeStatus.ACTIVE);
            lenient().when(runtimeCoordinator.getTransitionHistory("ling1")).thenReturn(Collections.emptyList());

            List<TransitionHistoryDTO> result = service.getTransitionHistory("ling1");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("getTransitionHistory 在有历史记录时应转换为 DTO 列表")
        void shouldReturnDtoListWhenHistoryExists() {
            DashboardService service = newService();
            when(runtimeCoordinator.getStatus("ling1")).thenReturn(RuntimeStatus.ACTIVE);
            List<TransitionRecord<RuntimeStatus>> history = Arrays.asList(
                    new TransitionRecord<RuntimeStatus>("ctx1", RuntimeStatus.INACTIVE, RuntimeStatus.ACTIVE, 100L),
                    new TransitionRecord<RuntimeStatus>("ctx2", RuntimeStatus.ACTIVE, RuntimeStatus.DEGRADED, 200L));
            when(runtimeCoordinator.getTransitionHistory("ling1")).thenReturn(history);

            List<TransitionHistoryDTO> result = service.getTransitionHistory("ling1");

            assertEquals(2, result.size());
            assertEquals("ctx1", result.get(0).getContextId());
            assertEquals("INACTIVE", result.get(0).getFrom());
            assertEquals("ACTIVE", result.get(0).getTo());
            assertEquals(100L, result.get(0).getTimestamp());
            assertEquals("DEGRADED", result.get(1).getTo());
        }
    }

    // ==================== 包扫描与部署 ====================

    @Nested
    @DisplayName("包扫描与部署")
    class PackageTests {

        @Test
        @DisplayName("scanPackages 在 lingHome 为 null 时应返回空列表")
        void scanPackagesShouldReturnEmptyWhenNoHome() {
            DashboardService service = newService();
            when(lingFrameConfig.getLingHome()).thenReturn(null);

            List<?> result = service.scanPackages();

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("scanPackages 在 lingHome 不存在时返回空列表")
        void scanPackagesShouldReturnEmptyWhenHomeNotExists() {
            DashboardService service = newService();
            when(lingFrameConfig.getLingHome()).thenReturn("non-existent-dir-12345");

            List<?> result = service.scanPackages();

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("deployPackage 在源文件找不到时应抛 LingInstallException")
        void deployPackageShouldThrowWhenFileNotFound() {
            DashboardService service = newService();
            // resolveSourceFile 依赖 lingFrameConfig，devMode 默认 false 且 lingHome 为 null → 返回 null
            when(lingFrameConfig.isDevMode()).thenReturn(false);
            when(lingFrameConfig.getLingHome()).thenReturn(null);

            assertThrows(LingInstallException.class,
                    () -> service.deployPackage("ling1", "1.0.0"));
        }
    }

    // ==================== 卸载（含文件删除） ====================

    @Nested
    @DisplayName("卸载含文件删除")
    class UninstallWithDeleteTests {

        @Test
        @DisplayName("uninstallLing(deleteFile=true) 应在卸载后尝试删除物理文件")
        void uninstallLingShouldAttemptFileDelete() {
            DashboardService service = newService();
            when(lifecycleEngine.undeployWithReport("ling1"))
                    .thenReturn(LingUninstallResult.triggered("ling1", null, Collections.emptyList()));
            when(lingFrameConfig.isDevMode()).thenReturn(false);
            when(lingFrameConfig.getLingHome()).thenReturn(null);

            LingUninstallResultDTO result = service.uninstallLing("ling1", true);

            assertNotNull(result);
            assertTrue(result.isUninstallTriggered());
            // canaryRouter 已删除，卸载清理改由 MigrationStateHolder.evict 处理（service 持 null 时不调用）
        }

        @Test
        @DisplayName("uninstallLing(version, deleteFile=true) 应按版本卸载并尝试删除")
        void uninstallLingVersionShouldAttemptFileDelete() {
            DashboardService service = newService();
            when(lifecycleEngine.undeployWithReport("ling1", "1.0.0"))
                    .thenReturn(LingUninstallResult.triggered("ling1", "1.0.0", Collections.emptyList()));
            when(lingFrameConfig.isDevMode()).thenReturn(false);
            when(lingFrameConfig.getLingHome()).thenReturn(null);

            LingUninstallResultDTO result = service.uninstallLing("ling1", "1.0.0", true);

            assertNotNull(result);
            assertEquals("1.0.0", result.getVersion());
            // canaryRouter 已删除，卸载清理改由 MigrationStateHolder.evict 处理（service 持 null 时不调用）
        }

        @Test
        @DisplayName("uninstallLing(deleteFile=false) 不应触发文件删除流程")
        void uninstallLingShouldNotDeleteWhenFlagFalse() {
            DashboardService service = newService();
            when(lifecycleEngine.undeployWithReport("ling1"))
                    .thenReturn(LingUninstallResult.triggered("ling1", null, Collections.emptyList()));

            LingUninstallResultDTO result = service.uninstallLing("ling1", false);

            assertNotNull(result);
            // deleteHomePackageFile 不会被调用，resolveSourceFile 也不会被调用
            verify(lingFrameConfig, never()).isDevMode();
        }
    }

    // ==================== 存储注入 ====================
    // 命中已删 setCanaryConfig API，StorageInjectionTests.setGovernanceStorageShouldPropagateToGovernanceSupport 已不适用
    // governanceStorage 的注入传播由 governanceSupport 单测覆盖

    // ==================== 安装异常路径 ====================

    @Nested
    @DisplayName("安装")
    class InstallTests {

        @Test
        @DisplayName("installLing 在文件无效时应抛 LingInstallException")
        void installLingShouldThrowWhenFileInvalid() {
            DashboardService service = newService();
            // 传入不存在的文件，parseDefinition 会返回 null 或抛异常，最终被包装为 LingInstallException
            File invalidFile = new File("non-existent-package.jar");

            assertThrows(LingInstallException.class,
                    () -> service.installLing(invalidFile));
        }
    }

    // ==================== 单参/双参卸载委托 ====================

    @Nested
    @DisplayName("卸载委托")
    class UninstallDelegateTests {

        @Test
        @DisplayName("单参 uninstallLing 应委托给双参版本且 deleteFile=false")
        void singleArgUninstallShouldDelegateWithoutDelete() {
            DashboardService service = newService();
            when(lifecycleEngine.undeployWithReport("ling1"))
                    .thenReturn(LingUninstallResult.triggered("ling1", null, Collections.emptyList()));

            LingUninstallResultDTO result = service.uninstallLing("ling1");

            assertNotNull(result);
            assertTrue(result.isUninstallTriggered());
            verify(lingFrameConfig, never()).isDevMode();
        }

        @Test
        @DisplayName("双参 uninstallLing(version) 应委托给三参版本且 deleteFile=false")
        void twoArgUninstallShouldDelegateWithoutDelete() {
            DashboardService service = newService();
            when(lifecycleEngine.undeployWithReport("ling1", "1.0.0"))
                    .thenReturn(LingUninstallResult.triggered("ling1", "1.0.0", Collections.emptyList()));

            LingUninstallResultDTO result = service.uninstallLing("ling1", "1.0.0");

            assertNotNull(result);
            assertEquals("1.0.0", result.getVersion());
            verify(lingFrameConfig, never()).isDevMode();
        }
    }
}
