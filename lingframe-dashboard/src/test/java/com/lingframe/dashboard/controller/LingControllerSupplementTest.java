package com.lingframe.dashboard.controller;

import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.GovernanceMetricsSnapshot;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.metrics.MetricsSnapshot;
import com.lingframe.core.routing.MigrationStateHolder;
import com.lingframe.dashboard.dto.ApiResponse;
import com.lingframe.dashboard.dto.DashboardSummaryDTO;
import com.lingframe.dashboard.dto.GovernanceMatrixRowDTO;
import com.lingframe.dashboard.dto.LingInfoDTO;
import com.lingframe.dashboard.dto.LingUninstallResultDTO;
import com.lingframe.dashboard.dto.RuntimeGovernanceReadinessDTO;
import com.lingframe.dashboard.dto.TrafficStatsDTO;
import com.lingframe.dashboard.dto.TransitionHistoryDTO;
import com.lingframe.dashboard.service.DashboardService;
import com.lingframe.dashboard.service.RuntimeDiagnosticsService;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.web.multipart.MultipartFile;

/**
 * LingController 补充测试。
 * <p>
 * 与 {@link LingControllerTest} 互补，覆盖 listLings、getLing、updateStatus、install、
 * uninstallVersion、reload、setCanary、getStats、resetStats、getGovernanceMatrix、
 * getCanaryDecision、getDashboardSummary、getTimeline、getTransitionHistory 等端点。
 * <p>
 * 测试方式：直接实例化 LingController，mock 所有依赖，不启动 Spring 上下文。
 */
@DisplayName("灵元控制器补充测试")
class LingControllerSupplementTest {

    /** 测试夹具：封装所有 mock 依赖与控制器实例，避免跨用例状态泄漏 */
    private static class ControllerFixture {
        final LingFrameConfig config = mock(LingFrameConfig.class);
        final DashboardService dashboardService = mock(DashboardService.class);
        final MetricsCollector metricsCollector = mock(MetricsCollector.class);
        final GovernanceMetricsCollector governanceMetricsCollector = mock(GovernanceMetricsCollector.class);
        final RuntimeDiagnosticsService runtimeDiagnosticsService = mock(RuntimeDiagnosticsService.class);
        final MigrationStateHolder migrationStateHolder =
                new MigrationStateHolder();
        final LingController controller;

        ControllerFixture(boolean installEnabled) {
            this.controller = new LingController(config, dashboardService, metricsCollector,
                    governanceMetricsCollector, runtimeDiagnosticsService, migrationStateHolder,
                    installEnabled);
        }
    }

    // ==================== listLings ====================

    @Nested
    @DisplayName("listLings - 获取灵元列表")
    class ListLingsTest {

        @Test
        @DisplayName("正常返回灵元列表")
        void shouldReturnLingList() {
            ControllerFixture fixture = new ControllerFixture(false);
            List<LingInfoDTO> infos = Collections.singletonList(
                    LingInfoDTO.builder().lingId("ling1").status("ACTIVE").build());
            when(fixture.dashboardService.getAllLingInfos()).thenReturn(infos);

            ApiResponse<List<LingInfoDTO>> response = fixture.controller.listLings();

            assertTrue(response.isSuccess());
            assertNotNull(response.getData());
            assertEquals(1, response.getData().size());
            assertEquals("ling1", response.getData().get(0).getLingId());
        }

        @Test
        @DisplayName("service 抛出异常时返回失败")
        void shouldReturnErrorOnException() {
            ControllerFixture fixture = new ControllerFixture(false);
            when(fixture.dashboardService.getAllLingInfos())
                    .thenThrow(new RuntimeException("db down"));

            ApiResponse<List<LingInfoDTO>> response = fixture.controller.listLings();

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("获取灵元列表失败"));
            assertTrue(response.getMessage().contains("db down"));
        }
    }

    // ==================== getLing ====================

    @Nested
    @DisplayName("getLing - 获取灵元详情")
    class GetLingTest {

        @Test
        @DisplayName("找到灵元时返回详情")
        void shouldReturnLingInfo() {
            ControllerFixture fixture = new ControllerFixture(false);
            LingInfoDTO info = LingInfoDTO.builder().lingId("ling1").status("ACTIVE").build();
            when(fixture.dashboardService.getLingInfo("ling1")).thenReturn(info);

            ApiResponse<LingInfoDTO> response = fixture.controller.getLing("ling1");

            assertTrue(response.isSuccess());
            assertNotNull(response.getData());
            assertEquals("ling1", response.getData().getLingId());
        }

        @Test
        @DisplayName("灵元不存在时返回错误")
        void shouldReturnErrorWhenNotFound() {
            ControllerFixture fixture = new ControllerFixture(false);
            when(fixture.dashboardService.getLingInfo("unknown")).thenReturn(null);

            ApiResponse<LingInfoDTO> response = fixture.controller.getLing("unknown");

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("灵元不存在"));
            assertTrue(response.getMessage().contains("unknown"));
        }

        @Test
        @DisplayName("service 抛出异常时返回失败")
        void shouldReturnErrorOnException() {
            ControllerFixture fixture = new ControllerFixture(false);
            when(fixture.dashboardService.getLingInfo("ling1"))
                    .thenThrow(new RuntimeException("fetch error"));

            ApiResponse<LingInfoDTO> response = fixture.controller.getLing("ling1");

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("获取灵元失败"));
        }
    }

    // ==================== updateStatus ====================

    @Nested
    @DisplayName("updateStatus - 更新灵元状态")
    class UpdateStatusTest {

        @Test
        @DisplayName("正常更新状态")
        void shouldUpdateStatus() {
            ControllerFixture fixture = new ControllerFixture(false);
            LingInfoDTO info = LingInfoDTO.builder().lingId("ling1").status("ACTIVE").build();
            when(fixture.dashboardService.updateStatus("ling1", RuntimeStatus.ACTIVE, "1.0.0"))
                    .thenReturn(info);
            LingController.LingStatusRequest request = new LingController.LingStatusRequest();
            request.setStatus(RuntimeStatus.ACTIVE);
            request.setVersion("1.0.0");

            ApiResponse<LingInfoDTO> response = fixture.controller.updateStatus("ling1", request);

            assertTrue(response.isSuccess());
            assertEquals("状态已更新", response.getMessage());
            assertNotNull(response.getData());
        }

        @Test
        @DisplayName("service 抛出异常时返回失败")
        void shouldReturnErrorOnException() {
            ControllerFixture fixture = new ControllerFixture(false);
            when(fixture.dashboardService.updateStatus("ling1", RuntimeStatus.STOPPING, "1.0.0"))
                    .thenThrow(new RuntimeException("transition not allowed"));
            LingController.LingStatusRequest request = new LingController.LingStatusRequest();
            request.setStatus(RuntimeStatus.STOPPING);
            request.setVersion("1.0.0");

            ApiResponse<LingInfoDTO> response = fixture.controller.updateStatus("ling1", request);

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("状态更新失败"));
        }
    }

    // ==================== install ====================

    @Nested
    @DisplayName("install - 安装灵元")
    class InstallTest {

        @Test
        @DisplayName("installEnabled=false 时拒绝安装")
        void shouldRejectWhenInstallDisabled() {
            ControllerFixture fixture = new ControllerFixture(false);
            MultipartFile file = mock(MultipartFile.class);

            ApiResponse<LingInfoDTO> response = fixture.controller.install(file);

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("安装接口未启用"));
        }

        @Test
        @DisplayName("文件为空时返回错误")
        void shouldReturnErrorWhenFileIsEmpty() {
            ControllerFixture fixture = new ControllerFixture(true);
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(true);

            ApiResponse<LingInfoDTO> response = fixture.controller.install(file);

            assertFalse(response.isSuccess());
            assertEquals("文件为空", response.getMessage());
        }

        @Test
        @DisplayName("文件名为 null 时返回错误")
        void shouldReturnErrorWhenFilenameIsNull() {
            ControllerFixture fixture = new ControllerFixture(true);
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getOriginalFilename()).thenReturn(null);

            ApiResponse<LingInfoDTO> response = fixture.controller.install(file);

            assertFalse(response.isSuccess());
            assertEquals("文件名为空", response.getMessage());
        }

        @Test
        @DisplayName("无效扩展名时返回错误")
        void shouldReturnErrorForInvalidExtension() {
            ControllerFixture fixture = new ControllerFixture(true);
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getOriginalFilename()).thenReturn("malicious.txt");

            ApiResponse<LingInfoDTO> response = fixture.controller.install(file);

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("无效的文件名或扩展名"));
        }

        @Test
        @DisplayName("非法 magic 头时返回错误")
        void shouldReturnErrorForInvalidMagicHeader() throws Exception {
            ControllerFixture fixture = new ControllerFixture(true);
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getOriginalFilename()).thenReturn("test.jar");
            // 非 ZIP magic 头
            when(file.getInputStream())
                    .thenReturn(new ByteArrayInputStream(new byte[]{0x00, 0x00, 0x00, 0x00}));

            ApiResponse<LingInfoDTO> response = fixture.controller.install(file);

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("非法文件格式"));
        }

        @Test
        @DisplayName("有效 JAR 文件时安装成功")
        void shouldInstallSuccessfully() throws Exception {
            ControllerFixture fixture = new ControllerFixture(true);
            MultipartFile file = mock(MultipartFile.class);
            when(file.isEmpty()).thenReturn(false);
            when(file.getOriginalFilename()).thenReturn("test.jar");
            // ZIP magic 头 50 4B 03 04
            when(file.getInputStream())
                    .thenReturn(new ByteArrayInputStream(new byte[]{0x50, 0x4B, 0x03, 0x04}));
            // 使用临时目录避免污染工作区
            when(fixture.config.getLingHome())
                    .thenReturn(System.getProperty("java.io.tmpdir") + "/ling-test-" + System.nanoTime());
            LingInfoDTO info = LingInfoDTO.builder().lingId("ling1").status("INACTIVE").build();
            when(fixture.dashboardService.installLing(any(File.class))).thenReturn(info);

            ApiResponse<LingInfoDTO> response = fixture.controller.install(file);

            assertTrue(response.isSuccess());
            assertEquals("安装成功", response.getMessage());
            assertNotNull(response.getData());
            assertEquals("ling1", response.getData().getLingId());
        }
    }

    // ==================== uninstallVersion ====================

    @Nested
    @DisplayName("uninstallVersion - 按版本卸载")
    class UninstallVersionTest {

        @Test
        @DisplayName("正常卸载指定版本")
        void shouldUninstallVersion() {
            ControllerFixture fixture = new ControllerFixture(false);
            LingUninstallResultDTO result = LingUninstallResultDTO.builder()
                    .lingId("ling1")
                    .uninstallTriggered(true)
                    .build();
            when(fixture.dashboardService.uninstallLing("ling1", "1.0.0", false))
                    .thenReturn(result);

            ApiResponse<LingUninstallResultDTO> response =
                    fixture.controller.uninstallVersion("ling1", "1.0.0", false);

            assertTrue(response.isSuccess());
            assertTrue(response.getMessage().contains("版本 1.0.0 卸载成功"));
            assertNotNull(response.getData());
            assertTrue(response.getData().isUninstallTriggered());
        }

        @Test
        @DisplayName("service 抛出异常时返回失败")
        void shouldReturnErrorOnException() {
            ControllerFixture fixture = new ControllerFixture(false);
            when(fixture.dashboardService.uninstallLing("ling1", "1.0.0", false))
                    .thenThrow(new RuntimeException("version not found"));

            ApiResponse<LingUninstallResultDTO> response =
                    fixture.controller.uninstallVersion("ling1", "1.0.0", false);

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("卸载特定版本失败"));
        }
    }

    // ==================== reload ====================

    @Nested
    @DisplayName("reload - 热重载灵元")
    class ReloadTest {

        @Test
        @DisplayName("非开发模式时拒绝热重载")
        void shouldRejectWhenNotDevMode() {
            ControllerFixture fixture = new ControllerFixture(false);
            when(fixture.config.isDevMode()).thenReturn(false);

            ApiResponse<LingInfoDTO> response = fixture.controller.reload("ling1", null);

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("热重载仅在开发模式下可用"));
        }

        @Test
        @DisplayName("开发模式下重载成功")
        void shouldReloadInDevMode() {
            ControllerFixture fixture = new ControllerFixture(false);
            when(fixture.config.isDevMode()).thenReturn(true);
            LingInfoDTO info = LingInfoDTO.builder().lingId("ling1").status("ACTIVE").build();
            when(fixture.dashboardService.reloadLing("ling1", null)).thenReturn(info);

            ApiResponse<LingInfoDTO> response = fixture.controller.reload("ling1", null);

            assertTrue(response.isSuccess());
            assertEquals("重载成功", response.getMessage());
            assertNotNull(response.getData());
        }

        @Test
        @DisplayName("开发模式下 service 抛出异常时返回失败")
        void shouldReturnErrorOnException() {
            ControllerFixture fixture = new ControllerFixture(false);
            when(fixture.config.isDevMode()).thenReturn(true);
            when(fixture.dashboardService.reloadLing("ling1", null))
                    .thenThrow(new RuntimeException("reload failed"));

            ApiResponse<LingInfoDTO> response = fixture.controller.reload("ling1", null);

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("重载失败"));
        }
    }

    // ==================== getStats ====================

    @Nested
    @DisplayName("getStats - 获取流量统计")
    class GetStatsTest {

        @Test
        @DisplayName("正常返回流量统计")
        void shouldReturnStats() {
            ControllerFixture fixture = new ControllerFixture(false);
            TrafficStatsDTO stats = TrafficStatsDTO.builder()
                    .lingId("ling1")
                    .totalRequests(100L)
                    .build();
            when(fixture.dashboardService.getTrafficStats("ling1")).thenReturn(stats);

            ApiResponse<TrafficStatsDTO> response = fixture.controller.getStats("ling1");

            assertTrue(response.isSuccess());
            assertNotNull(response.getData());
            assertEquals("ling1", response.getData().getLingId());
            assertEquals(100L, response.getData().getTotalRequests());
        }

        @Test
        @DisplayName("service 抛出异常时返回失败")
        void shouldReturnErrorOnException() {
            ControllerFixture fixture = new ControllerFixture(false);
            when(fixture.dashboardService.getTrafficStats("ling1"))
                    .thenThrow(new RuntimeException("stats error"));

            ApiResponse<TrafficStatsDTO> response = fixture.controller.getStats("ling1");

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("获取统计失败"));
        }
    }

    // ==================== resetStats ====================

    @Nested
    @DisplayName("resetStats - 重置流量统计")
    class ResetStatsTest {

        @Test
        @DisplayName("正常重置统计")
        void shouldResetStats() {
            ControllerFixture fixture = new ControllerFixture(false);

            ApiResponse<Void> response = fixture.controller.resetStats("ling1");

            assertTrue(response.isSuccess());
            assertEquals("统计已重置", response.getMessage());
        }

        @Test
        @DisplayName("service 抛出异常时返回失败")
        void shouldReturnErrorOnException() {
            ControllerFixture fixture = new ControllerFixture(false);
            doThrow(new RuntimeException("reset error"))
                    .when(fixture.dashboardService).resetTrafficStats("ling1");

            ApiResponse<Void> response = fixture.controller.resetStats("ling1");

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("重置失败"));
        }
    }

    // ==================== getGovernanceMatrix ====================

    @Nested
    @DisplayName("getGovernanceMatrix - 治理规则矩阵")
    class GetGovernanceMatrixTest {

        @Test
        @DisplayName("正常返回治理矩阵")
        void shouldReturnGovernanceMatrix() {
            ControllerFixture fixture = new ControllerFixture(false);
            LingInfoDTO.VersionInfo versionInfo = LingInfoDTO.VersionInfo.builder()
                    .version("1.0.0")
                    .isDefault(true)
                    .trafficWeight(100)
                    .build();
            LingInfoDTO.InvocationGovernance gov = LingInfoDTO.InvocationGovernance.builder()
                    .timeoutMs(1000)
                    .rateLimitPerSecond(100)
                    .maxConcurrentThreads(8)
                    .retryCount(2)
                    .cpuBudgetMsPerMinute(5000)
                    .memoryBudgetMb(256)
                    .build();
            LingInfoDTO.ResourcePermissions perms = LingInfoDTO.ResourcePermissions.builder().build();
            LingInfoDTO lingInfo = LingInfoDTO.builder()
                    .lingId("ling1")
                    .versionDetails(Collections.singletonList(versionInfo))
                    .invocationGovernance(gov)
                    .permissions(perms)
                    .build();
            when(fixture.dashboardService.getAllLingInfos())
                    .thenReturn(Collections.singletonList(lingInfo));

            ApiResponse<List<GovernanceMatrixRowDTO>> response =
                    fixture.controller.getGovernanceMatrix();

            assertTrue(response.isSuccess());
            assertNotNull(response.getData());
            assertEquals(1, response.getData().size());
            GovernanceMatrixRowDTO row = response.getData().get(0);
            assertEquals("ling1", row.getLingId());
            assertEquals("1.0.0", row.getVersion());
            assertTrue(row.isDefault());
            assertEquals(Integer.valueOf(1000), row.getTimeoutMs());
            assertEquals(Integer.valueOf(100), row.getRateLimitPerSecond());
        }

        @Test
        @DisplayName("灵元缺少 versionDetails 时跳过")
        void shouldSkipLingWithoutVersionDetails() {
            ControllerFixture fixture = new ControllerFixture(false);
            LingInfoDTO lingWithoutVersions = LingInfoDTO.builder()
                    .lingId("ling2")
                    .versionDetails(null)
                    .build();
            when(fixture.dashboardService.getAllLingInfos())
                    .thenReturn(Collections.singletonList(lingWithoutVersions));

            ApiResponse<List<GovernanceMatrixRowDTO>> response =
                    fixture.controller.getGovernanceMatrix();

            assertTrue(response.isSuccess());
            assertNotNull(response.getData());
            assertTrue(response.getData().isEmpty());
        }

        @Test
        @DisplayName("service 抛出异常时返回失败")
        void shouldReturnErrorOnException() {
            ControllerFixture fixture = new ControllerFixture(false);
            when(fixture.dashboardService.getAllLingInfos())
                    .thenThrow(new RuntimeException("matrix error"));

            ApiResponse<List<GovernanceMatrixRowDTO>> response =
                    fixture.controller.getGovernanceMatrix();

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("获取治理规则矩阵失败"));
        }
    }

    // ==================== getDashboardSummary ====================

    @Nested
    @DisplayName("getDashboardSummary - 仪表盘概览")
    class GetDashboardSummaryTest {

        @Test
        @DisplayName("正常返回概览数据（含生命周期事件）")
        void shouldReturnDashboardSummary() {
            ControllerFixture fixture = new ControllerFixture(false);
            // 健康指标快照
            MetricsSnapshot snapshot = new MetricsSnapshot();
            snapshot.setLingId("ling1");
            snapshot.setTotalRequests(100L);
            when(fixture.metricsCollector.getAllSnapshots())
                    .thenReturn(Collections.singletonList(snapshot));
            when(fixture.metricsCollector.getVersionSnapshots("ling1"))
                    .thenReturn(Collections.emptyMap());
            // 治理指标快照
            GovernanceMetricsSnapshot govSnapshot = GovernanceMetricsSnapshot.empty("ling1");
            Map<String, GovernanceMetricsSnapshot> govSummaries = new HashMap<>();
            govSummaries.put("ling1", govSnapshot);
            when(fixture.governanceMetricsCollector.getAllSummaries()).thenReturn(govSummaries);
            when(fixture.governanceMetricsCollector.getVersionSnapshots("ling1"))
                    .thenReturn(Collections.emptyMap());
            // 生命周期事件（超过 10 条以验证截断逻辑）
            List<DashboardService.LifecycleEvent> events = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                events.add(new DashboardService.LifecycleEvent(
                        "ling1", "1.0.0", "INSTALL", "title" + i, "desc" + i));
            }
            when(fixture.dashboardService.getLifecycleEvents(null)).thenReturn(events);
            // 运行时诊断
            when(fixture.runtimeDiagnosticsService.getCleanupCapabilities())
                    .thenReturn(Collections.emptyMap());
            when(fixture.runtimeDiagnosticsService.getGovernanceReadiness())
                    .thenReturn(RuntimeGovernanceReadinessDTO.builder()
                            .status("READY")
                            .summary("ok")
                            .build());

            ApiResponse<DashboardSummaryDTO> response =
                    fixture.controller.getDashboardSummary();

            assertTrue(response.isSuccess());
            assertNotNull(response.getData());
            assertNotNull(response.getData().getHealthMetrics());
            assertNotNull(response.getData().getGovernanceMetrics());
            // 截断后应只保留最近 10 条
            assertEquals(10, response.getData().getRecentEvents().size());
            assertNotNull(response.getData().getRuntimeGovernanceReadiness());
            assertEquals("READY", response.getData().getRuntimeGovernanceReadiness().getStatus());
        }

        @Test
        @DisplayName("空数据时返回空概览")
        void shouldReturnEmptySummary() {
            ControllerFixture fixture = new ControllerFixture(false);
            when(fixture.metricsCollector.getAllSnapshots()).thenReturn(Collections.emptyList());
            when(fixture.governanceMetricsCollector.getAllSummaries()).thenReturn(Collections.emptyMap());
            when(fixture.dashboardService.getLifecycleEvents(null)).thenReturn(Collections.emptyList());
            when(fixture.runtimeDiagnosticsService.getCleanupCapabilities())
                    .thenReturn(Collections.emptyMap());
            when(fixture.runtimeDiagnosticsService.getGovernanceReadiness())
                    .thenReturn(RuntimeGovernanceReadinessDTO.builder().status("LIMITED").build());

            ApiResponse<DashboardSummaryDTO> response =
                    fixture.controller.getDashboardSummary();

            assertTrue(response.isSuccess());
            assertNotNull(response.getData());
            assertTrue(response.getData().getHealthMetrics().isEmpty());
            assertTrue(response.getData().getRecentEvents().isEmpty());
        }

        @Test
        @DisplayName("service 抛出异常时返回失败")
        void shouldReturnErrorOnException() {
            ControllerFixture fixture = new ControllerFixture(false);
            when(fixture.metricsCollector.getAllSnapshots())
                    .thenThrow(new RuntimeException("summary error"));

            ApiResponse<DashboardSummaryDTO> response =
                    fixture.controller.getDashboardSummary();

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("获取概览数据失败"));
        }
    }

    // ==================== getTimeline ====================

    @Nested
    @DisplayName("getTimeline - 生命周期时间线")
    class GetTimelineTest {

        @Test
        @DisplayName("正常返回时间线事件")
        void shouldReturnTimeline() {
            ControllerFixture fixture = new ControllerFixture(false);
            List<DashboardService.LifecycleEvent> events = Arrays.asList(
                    new DashboardService.LifecycleEvent("ling1", "1.0.0", "INSTALL", "t1", "d1"),
                    new DashboardService.LifecycleEvent("ling1", "1.0.0", "ACTIVATE", "t2", "d2"));
            when(fixture.dashboardService.getLifecycleEvents("ling1")).thenReturn(events);

            ApiResponse<List<DashboardService.LifecycleEvent>> response =
                    fixture.controller.getTimeline("ling1");

            assertTrue(response.isSuccess());
            assertNotNull(response.getData());
            assertEquals(2, response.getData().size());
        }

        @Test
        @DisplayName("lingId 为 null 时返回全部事件")
        void shouldReturnAllEventsWhenLingIdIsNull() {
            ControllerFixture fixture = new ControllerFixture(false);
            when(fixture.dashboardService.getLifecycleEvents(null))
                    .thenReturn(Collections.emptyList());

            ApiResponse<List<DashboardService.LifecycleEvent>> response =
                    fixture.controller.getTimeline(null);

            assertTrue(response.isSuccess());
            assertNotNull(response.getData());
            assertTrue(response.getData().isEmpty());
        }

        @Test
        @DisplayName("service 抛出异常时返回失败")
        void shouldReturnErrorOnException() {
            ControllerFixture fixture = new ControllerFixture(false);
            when(fixture.dashboardService.getLifecycleEvents("ling1"))
                    .thenThrow(new RuntimeException("timeline error"));

            ApiResponse<List<DashboardService.LifecycleEvent>> response =
                    fixture.controller.getTimeline("ling1");

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("获取时间线事件失败"));
        }
    }

    // ==================== getTransitionHistory ====================

    @Nested
    @DisplayName("getTransitionHistory - 状态转换历史")
    class GetTransitionHistoryTest {

        @Test
        @DisplayName("正常返回状态转换历史")
        void shouldReturnTransitionHistory() {
            ControllerFixture fixture = new ControllerFixture(false);
            List<TransitionHistoryDTO> history = Collections.singletonList(
                    TransitionHistoryDTO.builder()
                            .contextId("ling1")
                            .from("INACTIVE")
                            .to("ACTIVE")
                            .timestamp(System.currentTimeMillis())
                            .build());
            when(fixture.dashboardService.getTransitionHistory("ling1")).thenReturn(history);

            ApiResponse<List<TransitionHistoryDTO>> response =
                    fixture.controller.getTransitionHistory("ling1");

            assertTrue(response.isSuccess());
            assertNotNull(response.getData());
            assertEquals(1, response.getData().size());
            assertEquals("ling1", response.getData().get(0).getContextId());
            assertEquals("INACTIVE", response.getData().get(0).getFrom());
            assertEquals("ACTIVE", response.getData().get(0).getTo());
        }

        @Test
        @DisplayName("service 抛出异常时返回失败")
        void shouldReturnErrorOnException() {
            ControllerFixture fixture = new ControllerFixture(false);
            when(fixture.dashboardService.getTransitionHistory("ling1"))
                    .thenThrow(new RuntimeException("history error"));

            ApiResponse<List<TransitionHistoryDTO>> response =
                    fixture.controller.getTransitionHistory("ling1");

            assertFalse(response.isSuccess());
            assertTrue(response.getMessage().contains("获取状态转换历史失败"));
        }
    }
}
