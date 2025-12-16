package com.lingframe.service;

import com.lingframe.api.annotation.LingReference;
import com.lingframe.api.context.PluginContext;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.sample.user.api.UserService;
import com.lingframe.sample.user.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class DemoService {

    @Autowired
    private PluginManager pluginManager;

    @Autowired
    private PluginContext pluginContext;

    @LingReference
    private UserService userService;

    /**
     * 演示通过FQSID调用插件服务
     */
    public String callUserService(String operation, String... params) {
        try {
            switch (operation) {
                case "query":
                    if (params.length > 0) {
                        Optional<User> userOptional = userService.queryUser(params[0]);
                        log.info("Query user result: {}", userOptional.orElse(null));
                        Optional<Object> optional = pluginContext.invoke("user-plugin:query_user", params[0]);
                        return "Query result: " + optional.orElse("Not found");
                    }
                    break;
                case "list":
                    List<User> userList = userService.listUsers();
                    log.info("List users result: {}", userList);
                    Optional<Object> optional = pluginContext.invoke("user-plugin:list_users");
                    return "List result: " + optional.orElse("Empty");
                case "create":
                    if (params.length > 1) {
                        User createdUser = userService.createUser(params[0], params[1]);
                        log.info("Create user result: {}", createdUser);
                        Optional<Object> createOptional = pluginContext.invoke("user-plugin:create_user", params[0], params[1]);
                        return "Create result: " + createOptional.orElse("Empty");
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