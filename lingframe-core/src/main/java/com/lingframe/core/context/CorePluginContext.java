package com.lingframe.core.context;

import com.lingframe.api.context.PluginContext;
import com.lingframe.api.event.LingEvent;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.core.strategy.GovernanceStrategy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class CorePluginContext implements PluginContext {

    private final String pluginId;

    /**
     *  向 Core/Runtime 内部暴露 PluginManager
     *  注意：此方法不在 PluginContext API 接口中，仅供框架内部强转使用
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
            throw new IllegalArgumentException("Service ID cannot be empty.");
        }

        // 1. 【安全检查】 使用服务 ID (FQSID) 进行鉴权
        // 对于通用的 invoke 协议，我们默认推导其操作类型为 EXECUTE
        AccessType accessType = GovernanceStrategy.inferAccessType("execute");

        if (!permissionService.isAllowed(pluginId, serviceId, accessType)) {
            log.error("Access Denied: Plugin [{}] attempted to invoke unauthorized service [{}]", pluginId, serviceId);
            // 审计：记录一次违规尝试
            permissionService.audit(pluginId, serviceId, "INVOKE_ATTEMPT", false);
            throw new PermissionDeniedException(
                    String.format("Access Denied: Plugin [%s] cannot access service [%s]", pluginId, serviceId)
            );
        }

        // 2. 【审计记录】 记录合规操作
        long start = System.nanoTime();
        boolean success = false;
        try {
            // 3. 【核心转发】 委托给 PluginManager 进行路由和调用
            // 注意：PluginManager 需要实现 invokeService 方法来处理路由
            Optional<T> result = pluginManager.invokeService(pluginId, serviceId, args);
            success = true;
            return result;
        } catch (PermissionDeniedException e) {
            throw e; // 权限异常直接抛出
        } catch (Exception e) {
            log.error("Service invocation failed for [{}]: {}", serviceId, e.getMessage(), e);
            throw new RuntimeException("Service invoke failed: " + e.getMessage(), e);
        } finally {
            long cost = System.nanoTime() - start;
            // 审计记录 (需从 FQSID 和 args 推导更详细的 Action/Resource)
            log.info("[AUDIT] Invoke FQSID={}, Success={}, Cost={}", serviceId, success, cost / 1_000_000);
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