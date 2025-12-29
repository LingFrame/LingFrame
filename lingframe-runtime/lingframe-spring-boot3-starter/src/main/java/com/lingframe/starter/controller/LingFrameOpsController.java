package com.lingframe.starter.controller;

import com.lingframe.core.plugin.PluginManager;
import com.lingframe.starter.config.LingFrameProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
public class LingFrameOpsController {

    private final PluginManager pluginManager;
    private final LingFrameProperties properties;

    /**
     * 查看所有插件状态
     */
    @GetMapping("/list")
    public List<Map<String, Object>> listPlugins() {
        return pluginManager.getAllPluginIds().stream().map(id -> {
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
    public String install(@RequestParam("file") MultipartFile file,
                          @RequestParam(value = "pluginId", required = false) String pluginId,
                          @RequestParam(value = "version", defaultValue = "1.0.0") String version) {
        try {
            // 1. 确定存放路径
            File pluginDir = new File(properties.getHome());
            if (!pluginDir.exists()) pluginDir.mkdirs();

            String originalFilename = file.getOriginalFilename();
            File targetFile = new File(pluginDir, originalFilename);

            // 2. 保存文件
            file.transferTo(targetFile);

            // 3. 如果没传 pluginId，尝试从文件名解析 (简单演示)
            String finalPluginId = (pluginId != null) ? pluginId : originalFilename.split("-")[0];

            // 4. 调用内核安装
            pluginManager.install(finalPluginId, version, targetFile);

            return "Success: " + finalPluginId + " installed.";
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
}