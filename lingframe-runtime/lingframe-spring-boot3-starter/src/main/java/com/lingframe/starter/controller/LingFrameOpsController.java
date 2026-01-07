package com.lingframe.starter.controller;

import com.lingframe.api.config.PluginDefinition;
import com.lingframe.core.dto.*;
import com.lingframe.core.enums.PluginStatus;
import com.lingframe.core.loader.PluginManifestLoader;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.starter.config.LingFrameProperties;
import com.lingframe.starter.service.LogStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.util.List;

/**
 * 灵珑内置运维控制台
 * 路径前缀: /lingframe/ops
 */
@Slf4j
@RestController
@RequestMapping("/lingframe/ops")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")  // 开发阶段允许跨域
@ConditionalOnProperty(prefix = "lingframe", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LingFrameOpsController {

    private final PluginManager pluginManager;

    private final LingFrameProperties properties;

    private final LogStreamService logStreamService;

    // ==================== 插件查询 ====================

    /**
     * 获取所有插件完整信息 (Dashboard 主接口)
     */
    @GetMapping("/plugins")
    public ApiResponse<List<PluginInfoDTO>> listPlugins() {
        try {
            List<PluginInfoDTO> plugins = pluginManager.getAllPluginInfos();
            return ApiResponse.ok(plugins);
        } catch (Exception e) {
            log.error("Failed to list plugins", e);
            return ApiResponse.error("获取插件列表失败: " + e.getMessage());
        }
    }

    /**
     * 获取单个插件详情
     */
    @GetMapping("/plugins/{pluginId}")
    public ApiResponse<PluginInfoDTO> getPlugin(@PathVariable String pluginId) {
        try {
            PluginInfoDTO info = pluginManager.getPluginInfo(pluginId);
            if (info == null) {
                return ApiResponse.error("插件不存在: " + pluginId);
            }
            return ApiResponse.ok(info);
        } catch (Exception e) {
            log.error("Failed to get plugin: {}", pluginId, e);
            return ApiResponse.error("获取插件信息失败: " + e.getMessage());
        }
    }

    // ==================== 生命周期管理 ====================

    /**
     * 更新插件状态 (激活/停止/卸载)
     */
    @PostMapping("/plugins/{pluginId}/status")
    public ApiResponse<PluginInfoDTO> updateStatus(
            @PathVariable String pluginId,
            @RequestBody PluginStatusDTO dto) {
        try {
            PluginStatus newStatus = PluginStatus.valueOf(dto.getStatus().toUpperCase());
            pluginManager.updatePluginStatus(pluginId, newStatus);

            PluginInfoDTO info = pluginManager.getPluginInfo(pluginId);
            return ApiResponse.ok("状态已更新", info);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("无效的状态值: " + dto.getStatus());
        } catch (IllegalStateException e) {
            return ApiResponse.error("状态转换失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to update status for plugin: {}", pluginId, e);
            return ApiResponse.error("状态更新失败: " + e.getMessage());
        }
    }

    /**
     * 安装/更新插件 (上传 JAR 包)
     */
    @PostMapping("/plugins/install")
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
            File pluginDir = new File(properties.getPluginHome());
            if (!pluginDir.exists()) pluginDir.mkdirs();
            File targetFile = new File(pluginDir, originalFilename);
            file.transferTo(targetFile);

            // 解析并安装
            PluginDefinition def = PluginManifestLoader.parseDefinition(targetFile);
            if (def == null) {
                return ApiResponse.error("无法解析插件定义文件 (plugin.yml)");
            }

            pluginManager.install(def, targetFile);

            PluginInfoDTO info = pluginManager.getPluginInfo(def.getId());
            return ApiResponse.ok("安装成功", info);
        } catch (Exception e) {
            log.error("Install failed", e);
            return ApiResponse.error("安装失败: " + e.getMessage());
        }
    }

    /**
     * 卸载插件
     */
    @DeleteMapping("/plugins/{pluginId}")
    public ApiResponse<Void> uninstall(@PathVariable String pluginId) {
        try {
            pluginManager.uninstall(pluginId);
            return ApiResponse.ok("卸载成功", null);
        } catch (Exception e) {
            log.error("Uninstall failed: {}", pluginId, e);
            return ApiResponse.error("卸载失败: " + e.getMessage());
        }
    }

    /**
     * 热重载插件 (开发模式)
     */
    @PostMapping("/plugins/{pluginId}/reload")
    public ApiResponse<PluginInfoDTO> reload(@PathVariable String pluginId) {
        if (!properties.isDevMode()) {
            return ApiResponse.error("热重载仅在开发模式下可用");
        }
        try {
            pluginManager.reload(pluginId);
            PluginInfoDTO info = pluginManager.getPluginInfo(pluginId);
            return ApiResponse.ok("重载成功", info);
        } catch (Exception e) {
            log.error("Reload failed: {}", pluginId, e);
            return ApiResponse.error("重载失败: " + e.getMessage());
        }
    }

    // ==================== 灰度发布 ====================

    /**
     * 设置灰度配置
     */
    @PostMapping("/plugins/{pluginId}/canary")
    public ApiResponse<CanaryConfigDTO> setCanary(
            @PathVariable String pluginId,
            @RequestBody CanaryConfigDTO dto) {
        try {
            pluginManager.setCanaryConfig(pluginId, dto.getPercent(), dto.getCanaryVersion());
            return ApiResponse.ok("灰度配置已更新", dto);
        } catch (Exception e) {
            log.error("Failed to set canary config for: {}", pluginId, e);
            return ApiResponse.error("灰度配置更新失败: " + e.getMessage());
        }
    }

    /**
     * 获取灰度配置
     */
    @GetMapping("/plugins/{pluginId}/canary")
    public ApiResponse<CanaryConfigDTO> getCanary(@PathVariable String pluginId) {
        try {
            return ApiResponse.ok(pluginManager.getCanaryConfig(pluginId));
        } catch (Exception e) {
            log.error("Failed to get canary config for: {}", pluginId, e);
            return ApiResponse.error("获取灰度配置失败: " + e.getMessage());
        }
    }

    // ==================== 流量统计 ====================

    /**
     * 获取流量统计
     */
    @GetMapping("/plugins/{pluginId}/stats")
    public ApiResponse<TrafficStatsDTO> getStats(@PathVariable String pluginId) {
        try {
            return ApiResponse.ok(pluginManager.getTrafficStats(pluginId));
        } catch (Exception e) {
            log.error("Failed to get stats for: {}", pluginId, e);
            return ApiResponse.error("获取统计失败: " + e.getMessage());
        }
    }

    /**
     * 重置流量统计
     */
    @PostMapping("/plugins/{pluginId}/stats/reset")
    public ApiResponse<Void> resetStats(@PathVariable String pluginId) {
        try {
            pluginManager.resetTrafficStats(pluginId);
            return ApiResponse.ok("统计已重置", null);
        } catch (Exception e) {
            log.error("Failed to reset stats: {}", pluginId, e);
            return ApiResponse.error("重置失败: " + e.getMessage());
        }
    }

    // ==================== 日志流 ====================

    /**
     * SSE 实时日志流端点
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs() {
        return logStreamService.createEmitter();
    }
}