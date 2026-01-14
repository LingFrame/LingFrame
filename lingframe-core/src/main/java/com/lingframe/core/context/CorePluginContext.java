package com.lingframe.core.context;

import com.lingframe.api.context.PluginContext;
import com.lingframe.api.event.LingEvent;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.core.exception.InvocationException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class CorePluginContext implements PluginContext {

    private final String pluginId;

    /**
     * 向 Core/Runtime 内部暴露 PluginManager
     * 注意：此方法不在 PluginContext API 接口中，仅供框架内部强转使用
     */
    @Getter
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
    public <T> Optional<T> invoke(String serviceId, Object... args) {
        if (serviceId == null || serviceId.isEmpty()) {
            throw new InvalidArgumentException("serviceId", "Service ID cannot be empty.");
        }

        try {
            return pluginManager.invokeService(this.pluginId, serviceId, args);
        } catch (PermissionDeniedException e) {
            throw e; // 权限异常直接抛出
        } catch (Exception e) {
            log.error("Service invocation failed for [{}]: {}", serviceId, e.getMessage(), e);
            throw new InvocationException("Service invoke failed: " + e.getMessage(), e);
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