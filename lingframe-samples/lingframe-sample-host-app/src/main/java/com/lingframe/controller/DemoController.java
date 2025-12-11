package com.lingframe.controller;

import com.lingframe.core.plugin.PluginManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/demo")
public class DemoController {

    @Autowired
    private PluginManager pluginManager;

    @GetMapping("/plugins")
    public Set<String> listPlugins() {
        return pluginManager.getInstalledPlugins();
    }

    @GetMapping("/plugin/{pluginId}/version")
    public String getPluginVersion(@PathVariable String pluginId) {
        return pluginManager.getPluginVersion(pluginId);
    }

    // 重启插件
    @PostMapping("/plugin/{pluginId}/reload")
    public String reloadPlugin(@PathVariable String pluginId) {
        try {
            pluginManager.reload(pluginId);
            return "Plugin " + pluginId + " reloaded successfully";
        } catch (Exception e) {
            return "Failed to reload plugin " + pluginId + ": " + e.getMessage();
        }
    }

    // 卸载插件
    @DeleteMapping("/plugin/{pluginId}")
    public String uninstallPlugin(@PathVariable String pluginId) {
        try {
            pluginManager.uninstall(pluginId);
            return "Plugin " + pluginId + " uninstalled successfully";
        } catch (Exception e) {
            return "Failed to uninstall plugin " + pluginId + ": " + e.getMessage();
        }
    }
}