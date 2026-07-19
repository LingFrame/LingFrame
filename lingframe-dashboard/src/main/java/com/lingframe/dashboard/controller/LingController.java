package com.lingframe.dashboard.controller;

import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.metrics.GovernanceMetricsCollector;
import com.lingframe.core.metrics.GovernanceMetricsSnapshot;
import com.lingframe.core.metrics.MetricsCollector;
import com.lingframe.core.metrics.MetricsSnapshot;
import com.lingframe.dashboard.dto.*;
import com.lingframe.dashboard.service.DashboardService;
import com.lingframe.dashboard.service.CanaryDecisionService;
import com.lingframe.dashboard.service.RuntimeDiagnosticsService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 灵元治理仪表盘控制器
 * 提供灵元安装、状态管理、权限审阅及流量监控等功能的 REST 接口。
 */
@Slf4j
@RestController
@RequestMapping("/lingframe/dashboard/lings")
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class LingController {

    private final LingFrameConfig lingFrameConfig;
    private final DashboardService dashboardService;
    private final MetricsCollector metricsCollector;
    private final GovernanceMetricsCollector governanceMetricsCollector;
    private final RuntimeDiagnosticsService runtimeDiagnosticsService;
    private final CanaryDecisionService canaryDecisionService;
    private final boolean installEnabled;

    public LingController(LingFrameConfig lingFrameConfig,
            DashboardService dashboardService,
            MetricsCollector metricsCollector,
            GovernanceMetricsCollector governanceMetricsCollector,
            RuntimeDiagnosticsService runtimeDiagnosticsService,
            CanaryDecisionService canaryDecisionService,
            @Value("${lingframe.dashboard.install-enabled:false}") boolean installEnabled) {
        this.lingFrameConfig = lingFrameConfig;
        this.dashboardService = dashboardService;
        this.metricsCollector = metricsCollector;
        this.governanceMetricsCollector = governanceMetricsCollector;
        this.runtimeDiagnosticsService = runtimeDiagnosticsService;
        this.canaryDecisionService = canaryDecisionService;
        this.installEnabled = installEnabled;
    }

    /**
     * 获取所有灵元的运行快照列表
     */
    @GetMapping
    public ApiResponse<List<LingInfoDTO>> listLings() {
        try {
            return ApiResponse.ok(dashboardService.getAllLingInfos());
        } catch (Exception e) {
            log.error("Failed to list Lings", e);
            return ApiResponse.error("获取灵元列表失败: " + e.getMessage());
        }
    }

    /**
     * 获取指定灵元的详细信息
     */
    @GetMapping("/{lingId}")
    public ApiResponse<LingInfoDTO> getLing(@PathVariable String lingId) {
        try {
            LingInfoDTO info = dashboardService.getLingInfo(lingId);
            if (info == null) {
                return ApiResponse.error("灵元不存在: " + lingId);
            }
            return ApiResponse.ok(info);
        } catch (Exception e) {
            log.error("Failed to get ling: {}", lingId, e);
            return ApiResponse.error("获取灵元失败: " + e.getMessage());
        }
    }

    /**
     * 更新灵元的运行时状态（激活、撤权等）
     */
    @PostMapping("/{lingId}/status")
    public ApiResponse<LingInfoDTO> updateStatus(
            @PathVariable String lingId,
            @RequestBody LingStatusRequest request) {
        try {
            LingInfoDTO info = dashboardService.updateStatus(lingId, request.getStatus(), request.getVersion());
            return ApiResponse.ok("状态已更新", info);
        } catch (Exception e) {
            log.error("Failed to update status: {}", lingId, e);
            return ApiResponse.error("状态更新失败: " + e.getMessage());
        }
    }

    /**
     * 安装或更新灵元
     * 通过上传灵元 JAR 包进行静态解析并执行冷启动部署。
     */
    @PostMapping("/install")
    public ApiResponse<LingInfoDTO> install(@RequestParam("file") MultipartFile file) {
        try {
            if (!installEnabled) {
                return ApiResponse.error("安装接口未启用，请设置 lingframe.dashboard.install-enabled=true");
            }
            if (file.isEmpty()) {
                return ApiResponse.error("文件为空");
            }

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null) {
                return ApiResponse.error("文件名为空");
            }
            
            // 防目录穿越：提取安全文件名
            String safeFilename = new File(originalFilename).getName();
            if (!safeFilename.endsWith(".jar") || safeFilename.contains("..")) {
                return ApiResponse.error("无效的文件名或扩展名");
            }

            // 魔法头探测（ZIP 格式 50 4B 03 04）
            byte[] magicBytes = new byte[4];
            try (InputStream is = file.getInputStream()) {
                if (is.read(magicBytes) != 4 || 
                    magicBytes[0] != 0x50 || magicBytes[1] != 0x4B || 
                    magicBytes[2] != 0x03 || magicBytes[3] != 0x04) {
                    return ApiResponse.error("非法文件格式，并非有效的 JAR 归档");
                }
            }

            // 保存文件
            File lingDir = new File(lingFrameConfig.getLingHome()).getAbsoluteFile();
            if (!lingDir.exists())
                lingDir.mkdirs();
            File targetFile = new File(lingDir, safeFilename);
            log.info("Saving uploaded ling to: {}", targetFile.getAbsolutePath());
            file.transferTo(targetFile);
            // 安装灵元
            LingInfoDTO info = dashboardService.installLing(targetFile);

            return ApiResponse.ok("安装成功", info);
        } catch (Exception e) {
            log.error("Install failed", e);
            return ApiResponse.error("安装失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/uninstall/{lingId}")
    public ApiResponse<LingUninstallResultDTO> uninstall(
            @PathVariable String lingId,
            @RequestParam(value = "deleteFile", defaultValue = "false") boolean deleteFile) {
        try {
            LingUninstallResultDTO result = dashboardService.uninstallLing(lingId, deleteFile);
            return ApiResponse.ok("卸载成功", result);
        } catch (Exception e) {
            log.error("Uninstall failed: {}", lingId, e);
            return ApiResponse.error("卸载失败: " + e.getMessage());
        }
    }

    /**
     * 按版本卸载灵元
     */
    @DeleteMapping("/uninstall/{lingId}/{version}")
    public ApiResponse<LingUninstallResultDTO> uninstallVersion(
            @PathVariable String lingId,
            @PathVariable String version,
            @RequestParam(value = "deleteFile", defaultValue = "false") boolean deleteFile) {
        try {
            LingUninstallResultDTO result = dashboardService.uninstallLing(lingId, version, deleteFile);
            return ApiResponse.ok("版本 " + version + " 卸载成功", result);
        } catch (Exception e) {
            log.error("Uninstall failed for: {}:{}", lingId, version, e);
            return ApiResponse.error("卸载特定版本失败: " + e.getMessage());
        }
    }


    /**
     * 热重载灵元
     * 仅在开发模式下允许，用于快速应用代码变更。
     */
    @PostMapping("/{lingId}/reload")
    public ApiResponse<LingInfoDTO> reload(
            @PathVariable String lingId,
            @RequestBody(required = false) LingReloadRequest request) {
        if (!lingFrameConfig.isDevMode()) {
            return ApiResponse.error("热重载仅在开发模式下可用");
        }
        try {
            String version = request != null ? request.getVersion() : null;
            LingInfoDTO info = dashboardService.reloadLing(lingId, version);
            return ApiResponse.ok("重载成功", info);
        } catch (Exception e) {
            log.error("Reload failed: {}", lingId, e);
            return ApiResponse.error("重载失败: " + e.getMessage());
        }
    }

    @PostMapping("/{lingId}/canary")
    public ApiResponse<Void> setCanary(
            @PathVariable String lingId,
            @RequestBody CanaryConfigDTO request) {
        try {
            dashboardService.setCanaryConfig(lingId, request.getPercent(), request.getCanaryVersion());
            return ApiResponse.ok("灰度配置已更新", null);
        } catch (Exception e) {
            log.error("Failed to set canary: {}", lingId, e);
            return ApiResponse.error("灰度配置失败: " + e.getMessage());
        }
    }

    @GetMapping("/{lingId}/stats")
    public ApiResponse<TrafficStatsDTO> getStats(@PathVariable String lingId) {
        try {
            return ApiResponse.ok(dashboardService.getTrafficStats(lingId));
        } catch (Exception e) {
            log.error("Failed to get stats: {}", lingId, e);
            return ApiResponse.error("获取统计失败: " + e.getMessage());
        }
    }

    @PostMapping("/{lingId}/stats/reset")
    public ApiResponse<Void> resetStats(@PathVariable String lingId) {
        try {
            dashboardService.resetTrafficStats(lingId);
            return ApiResponse.ok("统计已重置", null);
        } catch (Exception e) {
            log.error("Failed to reset stats: {}", lingId, e);
            return ApiResponse.error("重置失败: " + e.getMessage());
        }
    }

    /**
     * 治理规则总览矩阵：遍历所有灵元的所有版本，聚合治理配置和资源权限。
     * 用于快速发现配置漂移。
     */
    @GetMapping("/governance/matrix")
    public ApiResponse<List<GovernanceMatrixRowDTO>> getGovernanceMatrix() {
        try {
            List<GovernanceMatrixRowDTO> rows = new ArrayList<>();
            for (LingInfoDTO ling : dashboardService.getAllLingInfos()) {
                if (ling == null || ling.getVersionDetails() == null) continue;
                LingInfoDTO.InvocationGovernance gov = ling.getInvocationGovernance();
                LingInfoDTO.ResourcePermissions perms = ling.getPermissions();
                for (LingInfoDTO.VersionInfo v : ling.getVersionDetails()) {
                    rows.add(GovernanceMatrixRowDTO.builder()
                            .lingId(ling.getLingId())
                            .version(v.getVersion())
                            .isDefault(Boolean.TRUE.equals(v.getIsDefault()))
                            .isCanary(Boolean.TRUE.equals(v.getIsCanary()))
                            .trafficWeight(v.getTrafficWeight())
                            .timeoutMs(gov != null ? gov.getTimeoutMs() : null)
                            .rateLimitPerSecond(gov != null ? gov.getRateLimitPerSecond() : null)
                            .maxConcurrentThreads(gov != null ? gov.getMaxConcurrentThreads() : null)
                            .retryCount(gov != null ? gov.getRetryCount() : null)
                            .cpuBudgetMsPerMinute(gov != null ? gov.getCpuBudgetMsPerMinute() : null)
                            .memoryBudgetMb(gov != null ? gov.getMemoryBudgetMb() : null)
                            .dbRead(perms != null ? perms.isDbRead() : null)
                            .dbWrite(perms != null ? perms.isDbWrite() : null)
                            .cacheRead(perms != null ? perms.isCacheRead() : null)
                            .cacheWrite(perms != null ? perms.isCacheWrite() : null)
                            .build());
                }
            }
            return ApiResponse.ok(rows);
        } catch (Exception e) {
            log.error("Failed to get governance matrix", e);
            return ApiResponse.error("获取治理规则矩阵失败: " + e.getMessage());
        }
    }

    /**
     * 金丝雀发布决策辅助：基于稳定版与金丝雀版健康指标对比给出建议。
     */
    @GetMapping("/{lingId}/canary-decision")
    public ApiResponse<CanaryDecisionDTO> getCanaryDecision(@PathVariable String lingId) {
        try {
            return ApiResponse.ok(canaryDecisionService.decide(lingId));
        } catch (Exception e) {
            log.error("Failed to get canary decision: {}", lingId, e);
            return ApiResponse.error("获取金丝雀决策失败: " + e.getMessage());
        }
    }

    /**
     * 仪表盘轮询风暴优化聚合接口
     */
    @GetMapping("/dashboard-summary")
    public ApiResponse<DashboardSummaryDTO> getDashboardSummary() {
        try {
            Map<String, LingHealthViewDTO> healthMetrics = metricsCollector.getAllSnapshots().stream()
                    .collect(Collectors.toMap(
                            MetricsSnapshot::getLingId,
                            snapshot -> LingHealthViewDTO.builder()
                                    .summary(snapshot)
                                    .versions(metricsCollector.getVersionSnapshots(snapshot.getLingId()))
                                    .build(),
                            (existing, replacement) -> replacement
                    ));
                    
            Map<String, LingGovernanceMetricsViewDTO> governanceMetrics = governanceMetricsCollector.getAllSummaries().values().stream()
                    .collect(Collectors.toMap(
                            GovernanceMetricsSnapshot::getLingId,
                            snapshot -> LingGovernanceMetricsViewDTO.builder()
                                    .summary(snapshot)
                                    .versions(governanceMetricsCollector.getVersionSnapshots(snapshot.getLingId()))
                                    .build(),
                            (existing, replacement) -> replacement
                    ));

            // 最近生命周期事件：取全部事件的最后10条并倒序，用于概览页"最近事件"
            List<DashboardService.LifecycleEvent> allEvents = dashboardService.getLifecycleEvents(null);
            List<DashboardService.LifecycleEvent> recentEvents = new ArrayList<>();
            if (allEvents != null && !allEvents.isEmpty()) {
                int from = Math.max(0, allEvents.size() - 10);
                recentEvents = new ArrayList<>(allEvents.subList(from, allEvents.size()));
                Collections.reverse(recentEvents);
            }

            return ApiResponse.ok(DashboardSummaryDTO.builder()
                    .healthMetrics(healthMetrics)
                    .governanceMetrics(governanceMetrics)
                    .runtimeDiagnostics(runtimeDiagnosticsService.getCleanupCapabilities())
                    .runtimeGovernanceReadiness(runtimeDiagnosticsService.getGovernanceReadiness())
                    .recentEvents(recentEvents)
                    .build());
        } catch (Exception e) {
            log.error("Failed to get dashboard summary", e);
            return ApiResponse.error("获取概览数据失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取灵元生命周期事件
     */
    @GetMapping("/timeline")
    public ApiResponse<List<DashboardService.LifecycleEvent>> getTimeline(@RequestParam(value = "lingId", required = false) String lingId) {
        try {
            return ApiResponse.ok(dashboardService.getLifecycleEvents(lingId));
        } catch (Exception e) {
            log.error("Failed to get timeline events", e);
            return ApiResponse.error("获取时间线事件失败: " + e.getMessage());
        }
    }

    /**
     * 获取指定灵元的状态机转换历史。
     * <p>
     * 返回运行时状态机的环形缓冲区快照，用于故障回溯和状态转换时间线展示。
     */
    @GetMapping("/{lingId}/transitions")
    public ApiResponse<List<TransitionHistoryDTO>> getTransitionHistory(@PathVariable String lingId) {
        try {
            return ApiResponse.ok(dashboardService.getTransitionHistory(lingId));
        } catch (Exception e) {
            log.error("Failed to get transition history for ling: {}", lingId, e);
            return ApiResponse.error("获取状态转换历史失败: " + e.getMessage());
        }
    }

    // 内部类：请求体
    @Data
    public static class LingStatusRequest {
        private RuntimeStatus status;
        private String version;
    }

    @Data
    public static class LingReloadRequest {
        private String version;
    }
}
