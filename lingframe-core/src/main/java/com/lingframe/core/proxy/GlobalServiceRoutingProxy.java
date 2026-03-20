package com.lingframe.core.proxy;

import com.lingframe.api.exception.ServiceUnavailableException;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingServiceRegistry;
import com.lingframe.core.pipeline.InvocationPipelineEngine;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * 全局服务路由代理。
 * <p>
 * 作用：
 * 1. 作为灵核侧 `@LingReference` 注入的静态入口。
 * 2. 解决“先有代理、后有灵元”的时序问题，使灵元尚未启动时也能先创建代理对象。
 * 3. 在每次调用时动态解析目标灵元，始终路由到当前可用版本。
 * </p>
 * <p>
 * 设计要点：
 * - 不再持有 `Class<?>` 强引用，改为只保存接口名，避免类加载器泄漏。
 * - 通过 `LingRepository` 统一查询运行时，推进中心化路由。
 * - 复用 `SmartServiceProxy` 实例，避免每次调用都重复创建代理对象。
 * </p>
 */
@Slf4j
public class GlobalServiceRoutingProxy implements InvocationHandler {

    private final String callerLingId; // 通常为 "lingcore-app"
    private final String interfaceName; // 仅保存接口全限定名，不持有 Class 对象
    private final String targetLingId; // 用户显式指定的灵元 ID（可选）
    private final LingRepository lingRepository;
    private final InvocationPipelineEngine pipelineEngine;
    private final LingServiceRegistry lingServiceRegistry;

    // 复用 SmartServiceProxy，避免每次调用都创建新实例
    private volatile SmartServiceProxy cachedDelegate;
    private volatile String cachedDelegateLingId;

    public GlobalServiceRoutingProxy(String callerLingId, String interfaceName,
                                     String targetLingId, LingRepository lingRepository,
                                     InvocationPipelineEngine pipelineEngine) {
        this(callerLingId, interfaceName, targetLingId, lingRepository, pipelineEngine, null);
    }

    public GlobalServiceRoutingProxy(String callerLingId, String interfaceName,
                                     String targetLingId, LingRepository lingRepository,
                                     InvocationPipelineEngine pipelineEngine,
                                     LingServiceRegistry lingServiceRegistry) {
        this.callerLingId = callerLingId;
        this.interfaceName = interfaceName;
        this.targetLingId = targetLingId;
        this.lingRepository = lingRepository;
        this.pipelineEngine = pipelineEngine;
        this.lingServiceRegistry = lingServiceRegistry;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // `Object` 基础方法直接处理
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        // 实时解析目标灵元 ID
        String finalId = resolveTargetLingId();

        LingRuntime runtime = (finalId != null) ? lingRepository.getRuntime(finalId) : null;
        if (runtime == null) {
            throw new ServiceUnavailableException(interfaceName, "Service is currently offline");
        }

        // 复用或创建 `SmartServiceProxy`
        SmartServiceProxy delegate = getOrCreateDelegate(runtime.getLingId());
        return delegate.invoke(proxy, method, args);
    }

    private SmartServiceProxy getOrCreateDelegate(String lingId) {
        // 快速路径：如果目标灵元 ID 未变化，直接复用已有代理
        if (lingId.equals(cachedDelegateLingId) && cachedDelegate != null) {
            return cachedDelegate;
        }
        synchronized (this) {
            if (lingId.equals(cachedDelegateLingId) && cachedDelegate != null) {
                return cachedDelegate;
            }
            cachedDelegate = new SmartServiceProxy(callerLingId, lingId, interfaceName, pipelineEngine,
                    lingServiceRegistry);
            cachedDelegateLingId = lingId;
            return cachedDelegate;
        }
    }

    private String resolveTargetLingId() {
        // 如果注解已显式指定灵元 ID，直接使用
        if (targetLingId != null && !targetLingId.isEmpty()) {
            return targetLingId;
        }

        // 遍历所有灵元，寻找接口实现
        for (LingRuntime runtime : lingRepository.getAllRuntimes()) {
            if (!runtime.isAvailable())
                continue;
            try {
                LingInstance instance = runtime.getInstancePool().getDefault();
                if (instance != null && instance.getContainer() != null) {
                    ClassLoader cl = instance.getClassLoader();
                    if (cl == null) {
                        continue;
                    }
                    try {
                        Class<?> clazz = cl.loadClass(interfaceName);
                        if (instance.getContainer().getBean(clazz) != null) {
                            return runtime.getLingId();
                        }
                    } catch (ClassNotFoundException ignored) {
                        // 该灵元不包含此接口，继续搜索
                    }
                }
            } catch (Exception e) {
                log.trace("Error checking bean for ling {}: {}", runtime.getLingId(), e.getMessage());
            }
        }

        return null;
    }
}
