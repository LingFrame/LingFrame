package com.lingframe.core.context;

import com.lingframe.api.context.LingContext;
import com.lingframe.api.event.LingEvent;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.ling.LingManager;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.core.exception.InvocationException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class CoreLingContext implements LingContext {

    private final String lingId;

    /**
     * 向 Core/Runtime 内部暴露 LingManager
     * 注意：此方法不在 LingContext API 接口中，仅供框架内部强转使用
     */
    @Getter
    private final LingManager lingManager;
    private final PermissionService permissionService;
    private final EventBus eventBus;

    @Override
    public String getLingId() {
        return lingId;
    }

    @Override
    public Optional<String> getProperty(String key) {
        // 实际应从 Core 的配置中心获取受控配置
        return Optional.ofNullable(System.getProperty(key));
    }

    @Override
    public <T> Optional<T> getService(Class<T> serviceClass) {
        // 【关键】单元获取服务时，通过 Core 代理出去
        // 这里的 serviceClass.getName() 就是 Capability
        try {
            T service = lingManager.getService(lingId, serviceClass);
            return Optional.ofNullable(service);
        } catch (Exception e) {
            log.warn("Service get failed.", e);
            return Optional.empty();
        }
    }

    @Override
    public <T> Optional<T> invoke(String serviceId, Object... args) {
        if (serviceId == null || serviceId.isEmpty()) {
            throw new InvalidArgumentException("serviceId", "Service ID cannot be empty.");
        }

        try {
            return lingManager.invokeService(this.lingId, serviceId, args);
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
        log.info("Event published from {}: {}", lingId, event);
        eventBus.publish(event);
    }
}