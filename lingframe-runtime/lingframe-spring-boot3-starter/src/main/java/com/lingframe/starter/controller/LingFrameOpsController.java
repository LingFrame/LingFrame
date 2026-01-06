package com.lingframe.starter.controller;

import com.lingframe.api.config.PluginDefinition;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 灵珑内置运维控制台
 * 路径前缀: /lingframe/ops
 */
@Slf4j
@RestController
@RequestMapping("/lingframe/ops")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "lingframe", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LingFrameOpsController {

    private final PluginManager pluginManager;
    private final LingFrameProperties properties;

    private final LogStreamService logStreamService;

    /**
     * 查看所有插件状态
     */
    @GetMapping("/list")
    public List<Map<String, Object>> listPlugins() {
        return pluginManager.getInstalledPlugins().stream().map(id -> {
            Map<String, Object> info = new HashMap<>();
            info.put("pluginId", id);
            info.put("version", pluginManager.getPluginVersion(id));
            // info.put("status", ...);
            return info;
        }).collect(Collectors.toList());
    }

    /**
     * 安装/更新插件 (上传 JAR 包)
     */
    @PostMapping("/install")
    public String install(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return "Error: File is empty.";
            }

            // 确定存放路径
            File pluginDir = new File(properties.getPluginHome());
            if (!pluginDir.exists()) pluginDir.mkdirs();

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null) {
                return "Error: File name is null.";
            }

            if (!originalFilename.endsWith(".jar")) {
                return "Error: File must be a JAR package.";
            }
            File targetFile = new File(pluginDir, originalFilename);

            // 保存文件
            file.transferTo(targetFile);

            PluginDefinition pluginDefinition = PluginManifestLoader.parseDefinition(targetFile);
            if (pluginDefinition == null) {
                log.error("Install failed, yaml not exists");
                return "Error: yaml not exists";
            }

            // 调用内核安装
            pluginManager.install(pluginDefinition, targetFile);
            return "Success: " + pluginDefinition.getId() + " installed.";
        } catch (Exception e) {
            log.error("Install failed", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 卸载插件
     */
    @PostMapping("/uninstall/{pluginId}")
    public String uninstall(@PathVariable String pluginId) {
        pluginManager.uninstall(pluginId);
        return "Success: " + pluginId + " uninstalled.";
    }

    /**
     * 手动触发热重载 (开发模式用)
     */
    @PostMapping("/reload/{pluginId}")
    public String reload(@PathVariable String pluginId) {
        if (!properties.isDevMode()) {
            return "Error: Dev mode is disabled.";
        }
        pluginManager.reload(pluginId);
        return "Reload triggered for: " + pluginId;
    }

    /**
     * SSE 实时日志流端点
     * Header: text/event-stream
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs() {
        return logStreamService.createEmitter();
    }
}