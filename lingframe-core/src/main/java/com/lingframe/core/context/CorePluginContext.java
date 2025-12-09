package com.lingframe.core.context;

import com.lingframe.api.context.PluginContext;
import com.lingframe.api.event.LingEvent;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.plugin.PluginManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class CorePluginContext implements PluginContext {

    private final String pluginId;
    private final PluginManager pluginManager;
    private final PermissionService permissionService;
    private final EventBus eventBus;

    @Override
    public String getPluginId() {
        return pluginId;
    }

    @Override
    public Optional<String> getProperty(String key) {
        // 实际应从 Core 的配置中心获取受控配置
        return Optional.ofNullable(System.getProperty(key));
    }

    @Override
    public <T> Optional<T> getService(Class<T> serviceClass) {
        // 【关键】插件获取服务时，通过 Core 代理出去
        // 这里的 serviceClass.getName() 就是 Capability
        try {
            T service = pluginManager.getService(pluginId, serviceClass);
            return Optional.ofNullable(service);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public PermissionService getPermissionService() {
        return permissionService;
    }

    @Override
    public void publishEvent(LingEvent event) {
        log.info("Event published from {}: {}", pluginId, event);
        eventBus.publish(event);
    }
}