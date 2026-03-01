package com.lingframe.core.proxy;

import com.lingframe.core.exception.ServiceUnavailableException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.pipeline.InvocationPipelineEngine;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * 全局服务路由代理
 * <p>
 * 作用：
 * 1. 作为灵核端 @LingReference 注入的静态入口。
 * 2. 解决"鸡生蛋"问题：在单元还未启动时就能创建出代理对象。
 * 3. 动态路由：每次调用时，实时查找目标单元的最新版本。
 * <p>
 * V0.3.0 修复：
 * - 不再持有 Class<?> 引用（改用 String interfaceName），防止 ClassLoader 泄漏
 * - 不再持有 LingManager 引用（改用 LingRepository），推进去中心化
 * - 复用 SmartServiceProxy 实例，避免每次调用都创建新对象
 */
@Slf4j
public class GlobalServiceRoutingProxy implements InvocationHandler {

    private final String callerLingId; // 通常是 "lingcore-app"
    private final String interfaceName; // 🔥 仅存类全限定名，不持有 Class 对象
    private final String targetLingId; // 用户指定的单元ID (可选)
    private final LingRepository lingRepository;
    private final InvocationPipelineEngine pipelineEngine;

    // 🔥 复用 SmartServiceProxy，避免每次调用创建新实例
    private volatile SmartServiceProxy cachedDelegate;
    private volatile String cachedDelegateLingId;

    public GlobalServiceRoutingProxy(String callerLingId, String interfaceName,
            String targetLingId, LingRepository lingRepository,
            InvocationPipelineEngine pipelineEngine) {
        this.callerLingId = callerLingId;
        this.interfaceName = interfaceName;
        this.targetLingId = targetLingId;
        this.lingRepository = lingRepository;
        this.pipelineEngine = pipelineEngine;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Object 方法直接处理
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        // 实时获取目标 lingId
        String finalId = resolveTargetLingId();

        LingRuntime runtime = (finalId != null) ? lingRepository.getRuntime(finalId) : null;
        if (runtime == null) {
            throw new ServiceUnavailableException(interfaceName, "Service is currently offline");
        }

        // 复用或创建 SmartServiceProxy
        SmartServiceProxy delegate = getOrCreateDelegate(runtime.getLingId());
        return delegate.invoke(proxy, method, args);
    }

    private SmartServiceProxy getOrCreateDelegate(String lingId) {
        // 快速路径：如果目标 lingId 没变，复用已有的 delegate
        if (lingId.equals(cachedDelegateLingId) && cachedDelegate != null) {
            return cachedDelegate;
        }
        synchronized (this) {
            if (lingId.equals(cachedDelegateLingId) && cachedDelegate != null) {
                return cachedDelegate;
            }
            cachedDelegate = new SmartServiceProxy(callerLingId, lingId, pipelineEngine);
            cachedDelegateLingId = lingId;
            return cachedDelegate;
        }
    }

    private String resolveTargetLingId() {
        // 如果注解指定了 ID，直接用
        if (targetLingId != null && !targetLingId.isEmpty()) {
            return targetLingId;
        }

        // 遍历所有单元寻找实现
        for (LingRuntime runtime : lingRepository.getAllRuntimes()) {
            if (!runtime.isAvailable())
                continue;
            try {
                LingInstance instance = runtime.getInstancePool().getDefault();
                if (instance != null && instance.getContainer() != null) {
                    ClassLoader cl = instance.getContainer().getClassLoader();
                    try {
                        Class<?> clazz = cl.loadClass(interfaceName);
                        if (instance.getContainer().getBean(clazz) != null) {
                            return runtime.getLingId();
                        }
                    } catch (ClassNotFoundException ignored) {
                        // 该单元没有此接口，继续搜索
                    }
                }
            } catch (Exception e) {
                log.trace("Error checking bean for ling {}: {}", runtime.getLingId(), e.getMessage());
            }
        }

        return null;
    }
}