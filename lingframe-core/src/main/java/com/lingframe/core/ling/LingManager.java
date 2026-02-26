package com.lingframe.core.ling;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.proxy.GlobalServiceRoutingProxy;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;

/**
 * 单元生命周期管理器 (V0.3.0 外观模式版)
 * 已被废弃，其主要功能已完全委托给以下专用组件：
 * <ul>
 * <li>部署与卸载：{@link LingLifecycleEngine}</li>
 * <li>运行时数据：{@link LingRepository}</li>
 * <li>服务发现：{@link LingServiceRegistry}</li>
 * <li>调用和路由：{@link InvocationPipelineEngine}</li>
 * </ul>
 */
@Deprecated
@Slf4j
public class LingManager {

    private final LingLifecycleEngine lifecycleEngine;
    private final LingRepository lingRepository;
    private final LingServiceRegistry lingServiceRegistry;
    private final InvocationPipelineEngine pipelineEngine;

    public LingManager(LingLifecycleEngine lifecycleEngine,
            LingRepository lingRepository,
            LingServiceRegistry lingServiceRegistry,
            LingResourceManager resourceManager,
            InvocationPipelineEngine pipelineEngine,
            Object permissionService,
            Object eventBus) {
        this.lifecycleEngine = lifecycleEngine;
        this.lingRepository = lingRepository;
        this.lingServiceRegistry = lingServiceRegistry;
        this.pipelineEngine = pipelineEngine;
        // resourceManager, permissionService, eventBus 不再使用，保留构造兼容性
    }

    public void install(LingDefinition lingDefinition, File jarFile) {
        if (lifecycleEngine instanceof DefaultLingLifecycleEngine) {
            ((DefaultLingLifecycleEngine) lifecycleEngine).deploy(lingDefinition, jarFile, true,
                    Collections.emptyMap());
        } else {
            lifecycleEngine.deploy(jarFile);
        }
    }

    public void installDev(LingDefinition lingDefinition, File classesDir) {
        if (lifecycleEngine instanceof DefaultLingLifecycleEngine) {
            ((DefaultLingLifecycleEngine) lifecycleEngine).deploy(lingDefinition, classesDir, true,
                    Collections.emptyMap());
        } else {
            lifecycleEngine.deploy(classesDir);
        }
    }

    public void deployCanary(LingDefinition lingDefinition, File source, Map<String, String> labels) {
        if (lifecycleEngine instanceof DefaultLingLifecycleEngine) {
            ((DefaultLingLifecycleEngine) lifecycleEngine).deploy(lingDefinition, source, false, labels);
        } else {
            lifecycleEngine.deploy(source);
        }
    }

    public void reload(String lingId) {
        // V0.3.0 对 reload 提供更好的支持，这里简化实现只做告警
        log.warn("Reload is not fully supported in V0.3.0 facade. Use pipeline/lifecycle direct deploy.");
    }

    public void uninstall(String lingId) {
        lifecycleEngine.undeploy(lingId);
    }

    public <T> T getService(String callerLingId, Class<T> serviceType) {
        // V0.3.0 跨单元调用应该通过全局代理直接基于类名实现
        return getGlobalServiceProxy(callerLingId, serviceType, null);
    }

    @SuppressWarnings("unchecked")
    public <T> T getGlobalServiceProxy(String callerLingId, Class<T> serviceType, String targetLingId) {
        return (T) Proxy.newProxyInstance(
                serviceType.getClassLoader(),
                new Class[] { serviceType },
                new GlobalServiceRoutingProxy(callerLingId, serviceType, targetLingId, this, pipelineEngine));
    }

    public <T> Optional<T> invokeService(String callerLingId, String fqsid, Object... args) {
        // V0.3.0 应通过 Context 和 Pipeline 执行，为了兼容这里做个直接调用垫片，但不建议使用
        log.warn("Direct invokeService via LingManager is deprecated, use PipelineEngine");
        return Optional.empty();
    }

    public void registerProtocolService(String lingId, String fqsid, Object bean, Method method) {
        // 由于 V0.3.0 的 ServiceRegistry 退化成了 InvokableMethodCache 并存在于其他地方，暂留或者忽略
        // 实际上 V0.3.0 不再需要框架手工注册了，通过 Bean 后处理器会自动注册。
        String methodName = method.getName();
        Class<?>[] paramTypes = method.getParameterTypes();
        String[] paramNames = new String[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            paramNames[i] = paramTypes[i].getName();
        }
        lingServiceRegistry.registerServiceMetadata(fqsid, methodName, paramNames);
        // MethodHandle 等注册将在 V0.3.0 中放入 Runtime 独立配置
    }

    public Set<String> getInstalledLings() {
        Set<String> installed = new HashSet<>();
        for (LingRuntime runtime : lingRepository.getAllRuntimes()) {
            installed.add(runtime.getLingId());
        }
        return installed;
    }

    public String getLingVersion(String lingId) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        return runtime != null ? runtime.getInstancePool().getDefault().getVersion() : null;
    }

    public LingRuntime getRuntime(String lingId) {
        return lingRepository.getRuntime(lingId);
    }

    public boolean hasBean(String lingId, Class<?> beanType) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime != null && runtime.isAvailable()) {
            // Note: v0.3.0 bean finding is different, temporary mock
            LingInstance instance = runtime.getInstancePool().getDefault();
            if (instance != null && instance.getContainer() != null) {
                return instance.getContainer().getBean(beanType) != null;
            }
        }
        return false;
    }

    public void shutdown() {
        log.info("Shutting down LingManager facade...");
        // 原有大量的资源清理，现在应该分发到底层
        for (LingRuntime runtime : lingRepository.getAllRuntimes()) {
            runtime.getStateMachine().transition(RuntimeStatus.STOPPING);
            runtime.getInstancePool().shutdown();
        }
    }
}