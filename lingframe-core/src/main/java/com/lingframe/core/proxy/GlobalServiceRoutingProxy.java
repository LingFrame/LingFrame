package com.lingframe.core.proxy;

import com.lingframe.api.security.PermissionService;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.plugin.PluginInstance;
import com.lingframe.core.plugin.PluginManager;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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

        // 1. 确定目标插件 ID
        String finalTargetId = this.targetPluginId;

        // 如果注解没写 ID，则尝试自动发现
        if (finalTargetId == null || finalTargetId.isBlank()) {
            finalTargetId = resolveTargetPluginId();
        }

        if (finalTargetId == null) {
            throw new IllegalStateException(
                    "Service unavailable: No active plugin found for " + serviceInterface.getName()
            );
        }

        // 2. 🔥【核心】获取目标插件的实时引用
        // 我们不缓存这个 AtomicReference，而是每次从 Manager 获取 Slot
        // 这样即使插件被卸载后又重新安装（Slot对象变了），也能找到新的。
        AtomicReference<PluginInstance> instanceRef = pluginManager.getPluginInstanceRef(finalTargetId);

        if (instanceRef == null || instanceRef.get() == null) {
            // 如果缓存的 ID 对应的插件挂了，清除缓存再试一次（可选，这里简化处理直接报错）
            ROUTE_CACHE.remove(serviceInterface);
            throw new IllegalStateException(
                    String.format("Service [%s] unavailable: Plugin [%s] is not active.",
                            serviceInterface.getName(), targetPluginId)
            );
        }

        // 3. 构造智能代理 (SmartServiceProxy)
        // SmartServiceProxy 负责具体的 GovernanceKernel 调用、TCCL 切换、上下文构建
        // 这里创建对象的开销极小（都是引用传递），符合 JVM 逃逸分析优化场景
        SmartServiceProxy smartProxy = new SmartServiceProxy(
                callerPluginId,
                finalTargetId,
                instanceRef, // 传入原子引用，确保并发安全
                serviceInterface,
                governanceKernel,
                permissionService
        );

        // 4. 委托执行
        return smartProxy.invoke(proxy, method, args);
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