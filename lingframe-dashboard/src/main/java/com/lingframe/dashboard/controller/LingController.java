package com.lingframe.dashboard.controller;

import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.enums.LingStatus;
import com.lingframe.dashboard.dto.*;
import com.lingframe.dashboard.service.DashboardService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/lingframe/dashboard/lings")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@ConditionalOnProperty(prefix = "lingframe.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
public class LingController {

    private final LingFrameConfig lingFrameConfig;

    private final DashboardService dashboardService;

    @GetMapping
    public ApiResponse<List<LingInfoDTO>> listLings() {
        try {
            return ApiResponse.ok(dashboardService.getAllLingInfos());
        } catch (Exception e) {
            log.error("Failed to list Lings", e);
            return ApiResponse.error("获取单元列表失败: " + e.getMessage());
        }
    }

    @GetMapping("/{lingId}")
    public ApiResponse<LingInfoDTO> getLing(@PathVariable String lingId) {
        try {
            LingInfoDTO info = dashboardService.getLingInfo(lingId);
            if (info == null) {
                return ApiResponse.error("单元不存在: " + lingId);
            }
            return ApiResponse.ok(info);
        } catch (Exception e) {
            log.error("Failed to get ling: {}", lingId, e);
            return ApiResponse.error("获取单元失败: " + e.getMessage());
        }
    }

    @PostMapping("/{lingId}/status")
    public ApiResponse<LingInfoDTO> updateStatus(
            @PathVariable String lingId,
            @RequestBody LingStatusRequest request) {
        try {
            LingInfoDTO info = dashboardService.updateStatus(lingId, request.getStatus());
            return ApiResponse.ok("状态已更新", info);
        } catch (Exception e) {
            log.error("Failed to update status: {}", lingId, e);
            return ApiResponse.error("状态更新失败: " + e.getMessage());
        }
    }

    /**
     * 安装/更新单元 (上传 JAR 包)
     */
    @PostMapping("/install")
    public ApiResponse<LingInfoDTO> install(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ApiResponse.error("文件为空");
            }

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || !originalFilename.endsWith(".jar")) {
                return ApiResponse.error("文件必须是 JAR 包");
            }

            // 保存文件
            File lingDir = new File(lingFrameConfig.getLingHome()).getAbsoluteFile();
            if (!lingDir.exists())
                lingDir.mkdirs();
            File targetFile = new File(lingDir, originalFilename);
            log.info("Saving uploaded ling to: {}", targetFile.getAbsolutePath());
            file.transferTo(targetFile);
            // 安装单元
            LingInfoDTO info = dashboardService.installLing(targetFile);

            return ApiResponse.ok("安装成功", info);
        } catch (Exception e) {
            log.error("Install failed", e);
            return ApiResponse.error("安装失败: " + e.getMessage());
        }
    }

    /**
     * 卸载单元
     */
    @DeleteMapping("/uninstall/{lingId}")
    public ApiResponse<Void> uninstall(@PathVariable String lingId) {
        try {
            dashboardService.uninstallLing(lingId);
            return ApiResponse.ok("卸载成功", null);
        } catch (Exception e) {
            log.error("Uninstall failed: {}", lingId, e);
            return ApiResponse.error("卸载失败: " + e.getMessage());
        }
    }

    /**
     * 热重载单元 (开发模式)
     */
    @PostMapping("/{lingId}/reload")
    public ApiResponse<LingInfoDTO> reload(@PathVariable String lingId) {
        if (!lingFrameConfig.isDevMode()) {
            return ApiResponse.error("热重载仅在开发模式下可用");
        }
        try {
            LingInfoDTO info = dashboardService.reloadLing(lingId);
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

    // 内部类：请求体
    @Data
    public static class LingStatusRequest {
        private LingStatus status;
    }
}