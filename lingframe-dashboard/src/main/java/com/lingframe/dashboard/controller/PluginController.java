package com.lingframe.dashboard.controller;

import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.enums.PluginStatus;
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
@RequestMapping("/lingframe/dashboard/plugins")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@ConditionalOnProperty(
        prefix = "lingframe.dashboard",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class PluginController {

    private final LingFrameConfig lingFrameConfig;

    private final DashboardService dashboardService;

    @GetMapping
    public ApiResponse<List<PluginInfoDTO>> listPlugins() {
        try {
            return ApiResponse.ok(dashboardService.getAllPluginInfos());
        } catch (Exception e) {
            log.error("Failed to list plugins", e);
            return ApiResponse.error("获取插件列表失败: " + e.getMessage());
        }
    }

    @GetMapping("/{pluginId}")
    public ApiResponse<PluginInfoDTO> getPlugin(@PathVariable String pluginId) {
        try {
            PluginInfoDTO info = dashboardService.getPluginInfo(pluginId);
            if (info == null) {
                return ApiResponse.error("插件不存在: " + pluginId);
            }
            return ApiResponse.ok(info);
        } catch (Exception e) {
            log.error("Failed to get plugin: {}", pluginId, e);
            return ApiResponse.error("获取插件失败: " + e.getMessage());
        }
    }

    @PostMapping("/{pluginId}/status")
    public ApiResponse<PluginInfoDTO> updateStatus(
            @PathVariable String pluginId,
            @RequestBody PluginStatusRequest request) {
        try {
            PluginInfoDTO info = dashboardService.updateStatus(pluginId, request.getStatus());
            return ApiResponse.ok("状态已更新", info);
        } catch (Exception e) {
            log.error("Failed to update status: {}", pluginId, e);
            return ApiResponse.error("状态更新失败: " + e.getMessage());
        }
    }

    /**
     * 安装/更新插件 (上传 JAR 包)
     */
    @PostMapping("/install")
    public ApiResponse<PluginInfoDTO> install(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ApiResponse.error("文件为空");
            }

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || !originalFilename.endsWith(".jar")) {
                return ApiResponse.error("文件必须是 JAR 包");
            }

            // 保存文件
            File pluginDir = new File(lingFrameConfig.getPluginHome());
            if (!pluginDir.exists()) pluginDir.mkdirs();
            File targetFile = new File(pluginDir, originalFilename);
            file.transferTo(targetFile);
            // 安装插件
            PluginInfoDTO info = dashboardService.installPlugin(targetFile);

            return ApiResponse.ok("安装成功", info);
        } catch (Exception e) {
            log.error("Install failed", e);
            return ApiResponse.error("安装失败: " + e.getMessage());
        }
    }

    /**
     * 卸载插件
     */
    @DeleteMapping("/uninstall/{pluginId}")
    public ApiResponse<Void> uninstall(@PathVariable String pluginId) {
        try {
            dashboardService.uninstallPlugin(pluginId);
            return ApiResponse.ok("卸载成功", null);
        } catch (Exception e) {
            log.error("Uninstall failed: {}", pluginId, e);
            return ApiResponse.error("卸载失败: " + e.getMessage());
        }
    }

    /**
     * 热重载插件 (开发模式)
     */
    @PostMapping("/{pluginId}/reload")
    public ApiResponse<PluginInfoDTO> reload(@PathVariable String pluginId) {
        if (!lingFrameConfig.isDevMode()) {
            return ApiResponse.error("热重载仅在开发模式下可用");
        }
        try {
            PluginInfoDTO info = dashboardService.reloadPlugin(pluginId);
            return ApiResponse.ok("重载成功", info);
        } catch (Exception e) {
            log.error("Reload failed: {}", pluginId, e);
            return ApiResponse.error("重载失败: " + e.getMessage());
        }
    }

    @PostMapping("/{pluginId}/canary")
    public ApiResponse<Void> setCanary(
            @PathVariable String pluginId,
            @RequestBody CanaryConfigDTO request) {
        try {
            dashboardService.setCanaryConfig(pluginId, request.getPercent(), request.getCanaryVersion());
            return ApiResponse.ok("灰度配置已更新", null);
        } catch (Exception e) {
            log.error("Failed to set canary: {}", pluginId, e);
            return ApiResponse.error("灰度配置失败: " + e.getMessage());
        }
    }

    @GetMapping("/{pluginId}/stats")
    public ApiResponse<TrafficStatsDTO> getStats(@PathVariable String pluginId) {
        try {
            return ApiResponse.ok(dashboardService.getTrafficStats(pluginId));
        } catch (Exception e) {
            log.error("Failed to get stats: {}", pluginId, e);
            return ApiResponse.error("获取统计失败: " + e.getMessage());
        }
    }

    @PostMapping("/{pluginId}/stats/reset")
    public ApiResponse<Void> resetStats(@PathVariable String pluginId) {
        try {
            dashboardService.resetTrafficStats(pluginId);
            return ApiResponse.ok("统计已重置", null);
        } catch (Exception e) {
            log.error("Failed to reset stats: {}", pluginId, e);
            return ApiResponse.error("重置失败: " + e.getMessage());
        }
    }

    // 内部类：请求体
    @Data
    public static class PluginStatusRequest {
        private PluginStatus status;
    }
}