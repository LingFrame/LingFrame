package com.lingframe.core.proxy;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.core.plugin.PluginSlot;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局服务路由代理
 * * 作用：
 * 1. 作为 Host 端 @LingReference 注入的静态入口。
 * 2. 解决"鸡生蛋"问题：在插件还未启动时就能创建出代理对象。
 * 3. 动态路由：每次调用时，实时查找目标插件的最新版本（通过 AtomicReference）。
 */
@Slf4j
public class GlobalServiceRoutingProxy implements InvocationHandler {

    private final String callerPluginId; // 通常是 "host-app"
    private final Class<?> serviceInterface;// 目标接口
    private final String targetPluginId; // 用户指定的插件ID (可选)
    private final PluginManager pluginManager;
    private final GovernanceKernel governanceKernel;
    private final PermissionService permissionService;

    // 缓存：接口 -> 真正提供服务的插件ID (避免每次都遍历)
    private static final Map<Class<?>, String> ROUTE_CACHE = new ConcurrentHashMap<>();

    public GlobalServiceRoutingProxy(String callerPluginId, Class<?> serviceInterface,
                                     String targetPluginId, PluginManager pluginManager,
                                     GovernanceKernel governanceKernel, PermissionService permissionService) {
        this.callerPluginId = callerPluginId;
        this.serviceInterface = serviceInterface;
        this.targetPluginId = targetPluginId;
        this.pluginManager = pluginManager;
        this.governanceKernel = governanceKernel;
        this.permissionService = permissionService;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Object 方法直接处理
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        // 1. 实时获取 Slot (支持延迟绑定)
        String finalId = (targetPluginId != null && !targetPluginId.isEmpty())
                ? targetPluginId : resolveTargetPluginId();

        PluginSlot slot = (finalId != null) ? pluginManager.getSlot(finalId) : null;

        if (slot == null) {
            throw new IllegalStateException("Service [" + serviceInterface.getName() + "] is currently offline.");
        }

        // 2. 统一使用 SmartServiceProxy 执行治理和路由逻辑
        // 这样即使宿主调用，也能支持金丝雀分流！
        SmartServiceProxy delegate = new SmartServiceProxy(callerPluginId, slot, serviceInterface, governanceKernel, permissionService);
        return delegate.invoke(proxy, method, args);
    }

    private String resolveTargetPluginId() {
        // 如果注解指定了 ID，直接用
        if (targetPluginId != null && !targetPluginId.isEmpty()) {
            return targetPluginId;
        }

        // 查缓存
        if (ROUTE_CACHE.containsKey(serviceInterface)) {
            String cachedId = ROUTE_CACHE.get(serviceInterface);
            // 简单校验插件是否还活着
            if (pluginManager.getInstalledPlugins().contains(cachedId)) {
                return cachedId;
            }
            ROUTE_CACHE.remove(serviceInterface); // 缓存失效
        }

        // 遍历所有插件寻找实现
        for (String pluginId : pluginManager.getInstalledPlugins()) {
            if (pluginManager.hasBean(pluginId, serviceInterface)) {
                ROUTE_CACHE.put(serviceInterface, pluginId);
                return pluginId;
            }
        }

        return null;
    }
}