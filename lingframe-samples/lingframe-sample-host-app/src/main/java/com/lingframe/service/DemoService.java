package com.lingframe.service;

import com.lingframe.core.plugin.PluginManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class DemoService {

    @Autowired
    private PluginManager pluginManager;

    /**
     * 演示通过FQSID调用插件服务
     */
    public String callUserService(String operation, String... params) {
        try {
            switch (operation) {
                case "query":
                    if (params.length > 0) {
                        // 使用PluginManager直接调用服务
                        Optional<Object> result = pluginManager.invokeService("host-app", "user-plugin:query_user", params[0]);
                        return "Query result: " + result.orElse("Not found");
                    }
                    break;
                case "list":
                    Optional<Object> result = pluginManager.invokeService("host-app", "user-plugin:list_users");
                    return "List result: " + result.orElse("Empty");
                case "create":
                    if (params.length > 1) {
                        Optional<Object> createResult = pluginManager.invokeService("host-app", "user-plugin:create_user", params[0], params[1]);
                        return "Create result: " + createResult.orElse("Failed");
                    }
                    break;
                default:
                    return "Unknown operation: " + operation;
            }
            return "Invalid parameters for operation: " + operation;
        } catch (Exception e) {
            return "Error calling user service: " + e.getMessage();
        }
    }

    /**
     * 获取插件信息
     */
    public String getPluginInfo() {
        StringBuilder info = new StringBuilder();
        info.append("Installed plugins:\n");
        for (String pluginId : pluginManager.getInstalledPlugins()) {
            info.append("- ").append(pluginId)
                .append(" (version: ").append(pluginManager.getPluginVersion(pluginId)).append(")\n");
        }
        return info.toString();
    }
}