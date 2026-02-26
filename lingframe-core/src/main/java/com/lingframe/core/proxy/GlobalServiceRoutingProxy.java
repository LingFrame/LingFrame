package com.lingframe.core.proxy;

import com.lingframe.core.exception.ServiceUnavailableException;
import com.lingframe.core.ling.LingManager;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.pipeline.InvocationPipelineEngine;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局服务路由代理
 * * 作用：
 * 1. 作为 LINGCORE 端 @LingReference 注入的静态入口。
 * 2. 解决"鸡生蛋"问题：在单元还未启动时就能创建出代理对象。
 * 3. 动态路由：每次调用时，实时查找目标单元的最新版本（通过 AtomicReference）。
 */
@Slf4j
public class GlobalServiceRoutingProxy implements InvocationHandler {

    private final String callerLingId; // 通常是 "lingcore-app"
    private final Class<?> serviceInterface;// 目标接口
    private final String targetLingId; // 用户指定的单元ID (可选)
    private final LingManager lingManager;
    private final InvocationPipelineEngine pipelineEngine;

    // 缓存：接口类名 -> 真正提供服务的单元ID (避免每次都遍历)
    // 🔥 使用类名作为 Key 而非 Class 对象，避免持有 ClassLoader 引用导致泄漏
    private final Map<String, String> routeCache = new ConcurrentHashMap<>();

    public GlobalServiceRoutingProxy(String callerLingId, Class<?> serviceInterface,
            String targetLingId, LingManager lingManager,
            InvocationPipelineEngine pipelineEngine) {
        this.callerLingId = callerLingId;
        this.serviceInterface = serviceInterface;
        this.targetLingId = targetLingId;
        this.lingManager = lingManager;
        this.pipelineEngine = pipelineEngine;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Object 方法直接处理
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        // 实时获取 Runtime (支持延迟绑定)
        String finalId = (targetLingId != null && !targetLingId.isEmpty())
                ? targetLingId
                : resolveTargetLingId();

        LingRuntime runtime = (finalId != null) ? lingManager.getRuntime(finalId) : null;

        if (runtime == null) {
            throw new ServiceUnavailableException(serviceInterface.getName(), "Service is currently offline");
        }

        // 统一使用 SmartServiceProxy 执行治理和路由逻辑
        // 这样即使灵核调用，也能支持金丝雀分流！
        SmartServiceProxy delegate = new SmartServiceProxy(callerLingId, runtime.getLingId(), this.pipelineEngine);
        return delegate.invoke(proxy, method, args);
    }

    private String resolveTargetLingId() {
        // 如果注解指定了 ID，直接用
        if (targetLingId != null && !targetLingId.isEmpty()) {
            return targetLingId;
        }

        // 🔥 使用类名作为缓存 Key，避免持有 Class 引用
        String interfaceName = serviceInterface.getName();

        // 查缓存
        String cachedId = routeCache.get(interfaceName);
        if (cachedId != null) {
            // 简单校验单元是否还活着
            if (lingManager.getInstalledLings().contains(cachedId)) {
                return cachedId;
            }
            routeCache.remove(interfaceName); // 缓存失效
        }

        // 遍历所有单元寻找实现
        for (String lingId : lingManager.getInstalledLings()) {
            try {
                if (lingManager.hasBean(lingId, serviceInterface)) {
                    routeCache.put(interfaceName, lingId);
                    return lingId;
                }
            } catch (Exception e) {
                log.trace("Error checking bean for ling {}: {}", lingId, e.getMessage());
            }
        }

        return null;
    }
}