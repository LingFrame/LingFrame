package com.lingframe.core.ling;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.event.lifecycle.LingInstalledEvent;
import com.lingframe.api.event.lifecycle.LingInstallingEvent;
import com.lingframe.api.event.lifecycle.LingUninstalledEvent;
import com.lingframe.api.event.lifecycle.LingUninstallingEvent;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.context.DefaultLingContext;
import com.lingframe.core.dev.HotSwapWatcher;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.InstanceCoordinator;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.security.ApiOverrideVerifier;
import com.lingframe.core.security.DangerousApiVerifier;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.spi.LingLoaderFactory;
import com.lingframe.core.spi.LingSecurityVerifier;
import com.lingframe.core.resource.DefaultLeakDetector;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 灵元生命周期引擎
 * 生命周期逻辑：装载、隔离、权限申请等
 */
@Slf4j
public class DefaultLingLifecycleEngine implements LingLifecycleEngine {

    private final ContainerFactory containerFactory;
    private final LingLoaderFactory lingLoaderFactory;
    private final PermissionService permissionService;
    private final EventBus eventBus;

    private final LingRepository lingRepository;
    private final LingServiceRegistry lingServiceRegistry;

    private final List<LingSecurityVerifier> verifiers;
    private final LingFrameConfig lingFrameConfig;
    private final InvocationPipelineEngine pipelineEngine;
    private final LingUnloadCoordinator unloadCoordinator;
    private HotSwapWatcher hotSwapWatcher;

    private final InstanceCoordinator instanceCoordinator;
    private final RuntimeCoordinator runtimeCoordinator;

    public DefaultLingLifecycleEngine(ContainerFactory containerFactory,
                                      PermissionService permissionService,
                                      LingLoaderFactory lingLoaderFactory,
                                      List<LingSecurityVerifier> verifiers,
                                      EventBus eventBus,
                                      LingFrameConfig lingFrameConfig,
                                      LingRepository lingRepository,
                                      LingServiceRegistry lingServiceRegistry,
                                      InvocationPipelineEngine pipelineEngine,
                                      LingResourceManager lingResourceManager,
                                      LingUnloadCoordinator unloadCoordinator,
                                      RuntimeCoordinator runtimeCoordinator) {
        this(containerFactory, permissionService, lingLoaderFactory, verifiers, eventBus, lingFrameConfig,
                lingRepository, lingServiceRegistry, pipelineEngine, lingResourceManager, unloadCoordinator, runtimeCoordinator, null);
    }

    public DefaultLingLifecycleEngine(ContainerFactory containerFactory,
                                      PermissionService permissionService,
                                      LingLoaderFactory lingLoaderFactory,
                                      List<LingSecurityVerifier> verifiers,
                                      EventBus eventBus,
                                      LingFrameConfig lingFrameConfig,
                                      LingRepository lingRepository,
                                      LingServiceRegistry lingServiceRegistry,
                                      InvocationPipelineEngine pipelineEngine,
                                      LingResourceManager lingResourceManager,
                                      LingUnloadCoordinator unloadCoordinator,
                                      RuntimeCoordinator runtimeCoordinator,
                                      HotSwapWatcher hotSwapWatcher) {

        this.containerFactory = containerFactory;
        this.lingLoaderFactory = lingLoaderFactory;
        this.permissionService = permissionService;
        this.eventBus = eventBus;

        this.verifiers = new ArrayList<>();
        if (verifiers != null) {
            this.verifiers.addAll(verifiers);
        }

        boolean enableApiOverrideCheck = lingFrameConfig == null || lingFrameConfig.isApiOverrideCheckEnabled();
        if (enableApiOverrideCheck) {
            boolean hasApiOverrideVerifier = this.verifiers.stream()
                    .anyMatch(v -> v instanceof ApiOverrideVerifier);
            if (!hasApiOverrideVerifier) {
                this.verifiers.add(0, new ApiOverrideVerifier());
            }
        }

        boolean hasBytecodeVerifier = this.verifiers.stream()
                .anyMatch(v -> v instanceof DangerousApiVerifier);
        if (!hasBytecodeVerifier) {
            this.verifiers.add(new DangerousApiVerifier());
        }

        this.lingFrameConfig = lingFrameConfig;
        this.lingRepository = lingRepository;
        this.lingServiceRegistry = lingServiceRegistry;
        this.pipelineEngine = pipelineEngine;
        this.unloadCoordinator = unloadCoordinator != null
                ? unloadCoordinator
                : new LingUnloadCoordinator(pipelineEngine, new ArrayList<>(), lingResourceManager, new DefaultLeakDetector(eventBus, lingFrameConfig));
        this.hotSwapWatcher = hotSwapWatcher;
        this.instanceCoordinator = new InstanceCoordinator(eventBus);
        this.runtimeCoordinator = Objects.requireNonNull(runtimeCoordinator, "RuntimeCoordinator is required");
    }

    public void setHotSwapWatcher(HotSwapWatcher hotSwapWatcher) {
        this.hotSwapWatcher = hotSwapWatcher;
    }

    @Override
    public ClassLoader getClassLoader(String lingId) {
        return lingRepository.getRuntime(lingId).getClass().getClassLoader();
    }

    @Override
    public void deploy(LingDefinition lingDefinition, File sourceFile, boolean isDefault, Map<String, String> labels) {
        deployInternal(lingDefinition, sourceFile, isDefault, labels, false);
    }

    @Override
    public void deployForReload(LingDefinition lingDefinition, File sourceFile, boolean isDefault,
                                Map<String, String> labels) {
        deployInternal(lingDefinition, sourceFile, isDefault, labels, true);
    }

    private void deployInternal(LingDefinition lingDefinition, File sourceFile, boolean isDefault,
                                Map<String, String> labels, boolean allowSameVersion) {
        lingDefinition.validate();
        String lingId = lingDefinition.getId();
        String version = lingDefinition.getVersion();
        eventBus.publish(new LingInstallingEvent(lingId, version, sourceFile));

        ClassLoader lingClassLoader = null;
        LingContainer container = null;
        boolean isNewRuntime = false;
        try {
            for (LingSecurityVerifier verifier : verifiers) {
                verifier.verify(lingId, sourceFile);
            }

            if (lingRepository.hasRuntime(lingId)) {
                log.info("[{}] Preparing for upgrade", lingId);
                LingRuntime oldRuntime = lingRepository.getRuntime(lingId);
                if (!allowSameVersion && oldRuntime != null
                        && oldRuntime.getInstancePool().getInstance(version) != null) {
                    throw new IllegalStateException("Version " + version + " is already deployed for ling " + lingId
                            + ". Please uninstall it first.");
                }
            } else {
                isNewRuntime = true;
            }

            lingClassLoader = lingLoaderFactory.create(lingId, sourceFile, getClass().getClassLoader());
            container = containerFactory.create(lingDefinition, sourceFile, lingClassLoader);

            LingDefinition instanceDef = lingDefinition.copy();
            LingInstance instance = new LingInstance(container, instanceDef, eventBus);
            instance.addLabels(labels);

            // 1. 先注册到 RuntimeCoordinator（确保能接收实例状态事件）
            runtimeCoordinator.register(lingId);

            // 2. 驱动实例状态机：CREATED → LOADING
            instanceCoordinator.prepare(instance);

            LingRuntime runtime = lingRepository.getRuntime(lingId);
            if (runtime == null) {
                runtime = new LingRuntime(lingId, lingFrameConfig.getRuntimeConfig(), eventBus, instanceCoordinator);
                lingRepository.register(runtime);
            }

            LingContext context = new DefaultLingContext(lingId, lingRepository, lingServiceRegistry, pipelineEngine,
                    permissionService, eventBus);
            
            // 3. 驱动实例状态机：LOADING → STARTING
            instanceCoordinator.start(instance);

            // 启动灵元 Spring 容器（创建 Bean、注册 Controller、扫描 LingService）
            container.start(context);

            if (hotSwapWatcher != null
                    && lingFrameConfig != null
                    && lingFrameConfig.isDevMode()
                    && sourceFile != null
                    && sourceFile.isDirectory()) {
                hotSwapWatcher.register(lingId, sourceFile, lingDefinition);
            }

            runtime.getInstancePool().addInstance(instance, isDefault);
            
            // 4. 驱动实例状态机：STARTING → READY
            //    → 发布 InstanceStateChangedEvent
            //    → RuntimeCoordinator 聚合评估 → INACTIVE → ACTIVE
            //    → 发布 RuntimeStateChangedEvent
            instanceCoordinator.markReady(instance);

            if (lingDefinition.getGovernance() != null
                    && lingDefinition.getGovernance().getCapabilities() != null) {
                for (GovernancePolicy.CapabilityRule rule : lingDefinition.getGovernance()
                        .getCapabilities()) {
                    try {
                        AccessType accessType = AccessType.valueOf(rule.getAccessType().toUpperCase());
                        permissionService.grant(lingId, rule.getCapability(), accessType);
                    } catch (IllegalArgumentException e) {
                        log.warn("[{}] Invalid access type: {}", lingId, rule.getAccessType());
                    }
                }
            }

            // 实例就绪后，RuntimeCoordinator 会通过 InstanceStateChangedEvent 自动驱动状态变更
            // 无需手动触发状态转换

            eventBus.publish(new LingInstalledEvent(lingId, version));
            log.info("[{}] Installed successfully", lingId);

        } catch (Throwable t) {
            log.error("Failed to install ling: {} v{}", lingId, version, t);

            if (isNewRuntime) {
                lingRepository.deregister(lingId);
            }

            cleanupOnFailure(lingClassLoader, container);
            throw t;
        }
    }

    @Override
    public void undeploy(String lingId) {
        log.info("Uninstalling ling: {}", lingId);

        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            log.warn("Ling not found: {}", lingId);
            return;
        }

        // 流量排空：标记所有实例为 dying，等待存量请求完成
        drainInstances(lingId, runtime.getInstancePool().getActiveInstances(),
                runtime.getConfig().getForceCleanupDelaySeconds());

        // 完整卸载流程（包含 shutdown → tearDown → purge）
        doFullUndeploy(lingId, runtime);
    }

    @Override
    public void undeploy(String lingId, String version) {
        log.info("Uninstalling ling: {} version: {}", lingId, version);

        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            log.warn("Ling not found: {}", lingId);
            return;
        }

        // 1. 找到指定版本的实例（从活跃池查找）
        LingInstance targetInstance = runtime.getInstancePool().getInstance(version);
        if (targetInstance == null) {
            log.warn("Ling instance not found or already dying for: {}:{}", lingId, version);
            return;
        }

        // 2. 流量排空：标记实例为 dying，等待存量请求完成
        drainInstances(lingId, java.util.Collections.singletonList(targetInstance),
                runtime.getConfig().getForceCleanupDelaySeconds());

        // 3. 隔离并卸载该版本实例
        ClassLoader classLoader = targetInstance.getContainer().getClassLoader();
        instanceCoordinator.tearDown(targetInstance);
        runtime.getInstancePool().removeInstance(targetInstance);

        unloadCoordinator.onVersionUnload(lingId, version, classLoader);

        if (classLoader instanceof AutoCloseable) {
            try {
                ((AutoCloseable) classLoader).close();
            } catch (Exception e) {
                log.error("[{}] Failed to close ClassLoader for version {}", lingId, version, e);
            }
        }

        unloadCoordinator.detectLeak(lingId, version, classLoader);

        // 4. 全局状态检查
        List<LingInstance> remaining = runtime.getInstancePool().getAllInstances();
        if (remaining.isEmpty()) {
            log.info("[{}] No instances remaining after version {} unloaded. Cleaning up runtime.", lingId, version);
            // 触发全量清场（不包含已卸载的实例）
            doFullUndeploy(lingId, runtime);
        } else {
            log.info("[{}] Ling has {} instances remaining, skipping runtime cleanup.", lingId, remaining.size());
        }
    }

    @Override
    public void undeploy(String lingId, LingInstance instance) {
        if (instance == null) {
            return;
        }

        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            log.warn("Ling not found: {}", lingId);
            return;
        }

        log.info("Uninstalling ling: {} instance version: {}", lingId, instance.getVersion());

        // 若实例已不在池中，直接返回
        if (!runtime.getInstancePool().getAllInstances().contains(instance)) {
            log.warn("Ling instance not found or already removed for: {}:{}", lingId, instance.getVersion());
            return;
        }

        // 1. 流量排空：标记实例为 dying，等待存量请求完成
        drainInstances(lingId, java.util.Collections.singletonList(instance),
                runtime.getConfig().getForceCleanupDelaySeconds());

        // 2. 隔离并卸载该实例
        ClassLoader classLoader = instance.getContainer().getClassLoader();
        instanceCoordinator.tearDown(instance);
        runtime.getInstancePool().removeInstance(instance);

        unloadCoordinator.onVersionUnload(lingId, instance.getVersion(), classLoader);

        if (classLoader instanceof AutoCloseable) {
            try {
                ((AutoCloseable) classLoader).close();
            } catch (Exception e) {
                log.error("[{}] Failed to close ClassLoader for version {}", lingId, instance.getVersion(), e);
            }
        }

        unloadCoordinator.detectLeak(lingId, instance.getVersion(), classLoader);

        // 3. 全局状态检查
        List<LingInstance> remaining = runtime.getInstancePool().getAllInstances();
        if (remaining.isEmpty()) {
            log.info("[{}] No instances remaining after instance {} unloaded. Cleaning up runtime.",
                    lingId, instance.getVersion());
            doFullUndeploy(lingId, runtime);
        } else {
            log.info("[{}] Ling has {} instances remaining, skipping runtime cleanup.", lingId, remaining.size());
        }
    }

    private void doFullUndeploy(String lingId, LingRuntime runtime) {
        eventBus.publish(new LingUninstallingEvent(lingId));

        if (hotSwapWatcher != null) {
            hotSwapWatcher.unregister(lingId);
        }

        // 1. 通过 RuntimeCoordinator 进入 STOPPING 状态（拒绝新请求）
        RuntimeStatus current = runtime.getStateMachine().current();
        if (current != RuntimeStatus.STOPPING && current != RuntimeStatus.INACTIVE && current != RuntimeStatus.REMOVED) {
            runtimeCoordinator.shutdown(lingId);
        }

        // 2. 逐个卸载底层剩余的所有实例
        //    instanceCoordinator.tearDown() → 发布 InstanceStateChangedEvent(DEAD)
        //    → RuntimeCoordinator.tryFinishShutdown() → STOPPING→REMOVED
        List<LingInstance> instances = runtime.getInstancePool().getAllInstances();
        for (LingInstance instance : instances) {
            // 🔥 先获取 ClassLoader，再 tearDown
            ClassLoader classLoader = instance.getContainer().getClassLoader();

            instanceCoordinator.tearDown(instance);
            runtime.getInstancePool().removeInstance(instance);

            // 🔥 彻底卸载的关键：清理资源并检测泄漏
            unloadCoordinator.onVersionUnload(lingId, instance.getVersion(), classLoader);

            // 显式关闭类加载器 (释放 Jar 句柄)
            if (classLoader instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) classLoader).close();
                } catch (Exception e) {
                    log.error("[{}] Failed to close ClassLoader", lingId, e);
                }
            }

            // 延迟触发泄漏检测
            unloadCoordinator.detectLeak(lingId, instance.getVersion(), classLoader);
        }

        // 3. 清理注册表中的暴露条目
        lingServiceRegistry.evict(lingId);
        List<String> remainingServices = lingServiceRegistry.getServicesByLingId(lingId);
        if (remainingServices != null && !remainingServices.isEmpty()) {
            log.warn("[{}] Service registry still has {} entries after evict, forcing cleanup",
                    lingId, remainingServices.size());
            lingServiceRegistry.evict(lingId);
        }

        // 4. 统一卸载后置清理
        unloadCoordinator.onLingUnload(lingId);

        // 5. 彻底解绑监听与权限
        eventBus.unsubscribeAll(lingId);
        permissionService.removeLing(lingId);

        // 6. 清理 RuntimeCoordinator 内存（状态已由 tryFinishShutdown 自动转为 REMOVED）
        runtimeCoordinator.purge(lingId);

        // 7. 从仓储移除引用
        lingRepository.deregister(lingId);

        eventBus.publish(new LingUninstalledEvent(lingId));
    }

    private void cleanupOnFailure(ClassLoader classLoader, LingContainer container) {
        if (container != null) {
            try {
                container.stop();
            } catch (Exception e) {
                log.warn("Failed to stop container", e);
            }
        }

        unloadCoordinator.onFailureCleanup(classLoader);

        if (classLoader instanceof AutoCloseable) {
            try {
                ((AutoCloseable) classLoader).close();
            } catch (Exception e) {
                log.warn("Failed to close classloader", e);
            }
        }
    }

    /**
     * 流量排空：标记目标实例为 dying（拒绝新请求），轮询等待存量请求完成。
     * <p>
     * 关键点：
     * - 使用 {@link InstanceCoordinator} 驱动 STOPPING，确保实例事件与 Runtime 快照联动；
     * - {@link LingInstance#isIdle()} 用于判断存量请求是否清空；
     * - 超时保护：超过 timeoutSeconds 后强制继续，避免无限等待。
     *
     * @param lingId         灵元 ID（仅用于日志）
     * @param instances      需要排空的实例列表
     * @param timeoutSeconds 排空超时秒数
     */
    private void drainInstances(String lingId, List<LingInstance> instances, int timeoutSeconds) {
        if (instances == null || instances.isEmpty()) {
            return;
        }

        // 第一步：标记所有实例为 dying，拒绝新请求
        log.info("[{}] Marking {} instances as STOPPING for drain", lingId, instances.size());
        for (LingInstance instance : instances) {
            if (!instance.isDying()) {
                try {
                    instanceCoordinator.stop(instance);
                } catch (Exception e) {
                    log.debug("[{}] Instance {} already in terminal state", lingId, instance.getVersion());
                }
            }
        }

        log.info("[{}] Draining {} instances, timeout={}s...", lingId, instances.size(), timeoutSeconds);

        // 第二步：轮询等待所有实例变为 idle
        long deadlineMs = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        boolean allIdle = false;

        while (System.currentTimeMillis() < deadlineMs) {
            allIdle = true;
            for (LingInstance instance : instances) {
                if (!instance.isIdle()) {
                    allIdle = false;
                    break;
                }
            }
            if (allIdle) {
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[{}] Drain interrupted", lingId);
                return;
            }
        }

        if (allIdle) {
            log.info("[{}] All instances drained successfully", lingId);
        } else {
            // 超时后强制继续，记录残留的活跃请求数
            for (LingInstance instance : instances) {
                if (!instance.isIdle()) {
                    log.warn("[{}] Force proceeding: instance {} still has {} active requests",
                            lingId, instance.getVersion(), instance.getActiveRequestCount());
                }
            }
        }
    }
}
