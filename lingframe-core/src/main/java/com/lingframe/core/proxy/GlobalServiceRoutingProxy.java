package com.lingframe.core.proxy;

import com.lingframe.core.plugin.PluginManager;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局服务路由代理
 * 职责：当宿主调用接口方法时，动态在所有插件中寻找谁实现了这个接口，并转发调用。
 */
@Slf4j
public class GlobalServiceRoutingProxy implements InvocationHandler {

    private final String callerPluginId; // 通常是 "host-app"
    private final Class<?> interfaceClass;
    private final String targetPluginId; // 用户指定的插件ID (可选)
    private final PluginManager pluginManager;

    // 缓存：接口 -> 真正提供服务的插件ID (避免每次都遍历)
    private static final Map<Class<?>, String> ROUTE_CACHE = new ConcurrentHashMap<>();

    public GlobalServiceRoutingProxy(String callerPluginId, Class<?> interfaceClass, String targetPluginId, PluginManager pluginManager) {
        this.callerPluginId = callerPluginId;
        this.interfaceClass = interfaceClass;
        this.targetPluginId = targetPluginId;
        this.pluginManager = pluginManager;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Object 方法直接处理
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        // 1. 确定目标插件
        String finalTargetPluginId = resolveTargetPluginId();

        if (finalTargetPluginId == null) {
            throw new IllegalStateException("No active plugin found implementing service: " + interfaceClass.getName());
        }

        // 2. 获取目标插件的 Slot 代理 (复用已有的 SmartServiceProxy)
        // 这一步会触发 TCCL 劫持、权限检查、审计等所有原有逻辑
        Object serviceProxy = pluginManager.getService(callerPluginId, finalTargetPluginId, interfaceClass);

        // 3. 执行调用
        return method.invoke(serviceProxy, args);
    }

    private String resolveTargetPluginId() {
        // 如果注解指定了 ID，直接用
        if (targetPluginId != null && !targetPluginId.isEmpty()) {
            return targetPluginId;
        }

        // 查缓存
        if (ROUTE_CACHE.containsKey(interfaceClass)) {
            String cachedId = ROUTE_CACHE.get(interfaceClass);
            // 简单校验插件是否还活着
            if (pluginManager.getInstalledPlugins().contains(cachedId)) {
                return cachedId;
            }
            ROUTE_CACHE.remove(interfaceClass); // 缓存失效
        }

        // 遍历所有插件寻找实现
        for (String pluginId : pluginManager.getInstalledPlugins()) {
            if (pluginManager.hasBean(pluginId, interfaceClass)) {
                ROUTE_CACHE.put(interfaceClass, pluginId);
                return pluginId;
            }
        }

        return null;
    }
}