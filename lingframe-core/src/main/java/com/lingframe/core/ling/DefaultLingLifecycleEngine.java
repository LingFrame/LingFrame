package com.lingframe.core.ling;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.event.lifecycle.LingInstalledEvent;
import com.lingframe.api.event.lifecycle.LingInstallingEvent;
import com.lingframe.api.event.lifecycle.LingUninstalledEvent;
import com.lingframe.api.event.lifecycle.LingUninstallingEvent;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.context.CoreLingContext;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.InstanceCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.security.DangerousApiVerifier;
import com.lingframe.core.security.ApiOverrideVerifier;
import com.lingframe.core.spi.*;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.*;

/**
 * 灵元生命周期引擎 (V0.3.0)
 * 生命周期逻辑（装载、隔离、权限申请）
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
    private final List<ResourceGuard> resourceGuards;

    private final InstanceCoordinator instanceCoordinator;

    public DefaultLingLifecycleEngine(ContainerFactory containerFactory,
            PermissionService permissionService,
            LingLoaderFactory lingLoaderFactory,
            List<LingSecurityVerifier> verifiers,
            EventBus eventBus,
            LingFrameConfig lingFrameConfig,
            LingRepository lingRepository,
            LingServiceRegistry lingServiceRegistry,
            InvocationPipelineEngine pipelineEngine,
            List<ResourceGuard> resourceGuards) {

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
        this.resourceGuards = resourceGuards != null ? new ArrayList<>(resourceGuards) : new ArrayList<>();
        this.instanceCoordinator = new InstanceCoordinator(eventBus);
    }

    @Override
    public void deploy(LingDefinition lingDefinition, File sourceFile, boolean isDefault, Map<String, String> labels) {
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
                if (oldRuntime != null && oldRuntime.getInstancePool().getInstance(version) != null) {
                    throw new IllegalStateException("Version " + version + " is already deployed for ling " + lingId
                            + ". Please uninstall it first.");
                }
            } else {
                isNewRuntime = true;
            }

            lingClassLoader = lingLoaderFactory.create(lingId, sourceFile, getClass().getClassLoader());
            container = containerFactory.create(lingId, sourceFile, lingClassLoader);

            LingDefinition instanceDef = lingDefinition.copy();
            LingInstance instance = new LingInstance(container, instanceDef, eventBus);
            instance.addLabels(labels);

            // FSM state flow to loading
            instanceCoordinator.prepare(instance);

            LingRuntime runtime = lingRepository.getRuntime(lingId);
            if (runtime == null) {
                runtime = new LingRuntime(lingId, lingFrameConfig.getRuntimeConfig(), eventBus);
                lingRepository.register(runtime);
            }

            LingContext context = new CoreLingContext(lingId, lingRepository, lingServiceRegistry, pipelineEngine,
                    permissionService, eventBus);
            instanceCoordinator.start(instance);

            // 启动灵元 Spring 容器（创建 Bean、注册 Controller、扫描 LingService）
            container.start(context);

            runtime.getInstancePool().addInstance(instance, isDefault);
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

            // 实例就绪后，将状态机从 INACTIVE → ACTIVE
            if (runtime.getStateMachine().current() == RuntimeStatus.INACTIVE) {
                runtime.getStateMachine().transition(RuntimeStatus.ACTIVE);
                log.info("[{}] State transitioned to ACTIVE", lingId);
            }

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

        // ① 先将宏观状态设为 STOPPING，MacroStateGuardFilter 会拒绝新请求
        if (runtime.getStateMachine().current() != RuntimeStatus.STOPPING) {
            runtime.getStateMachine().transition(RuntimeStatus.STOPPING);
        }

        // ② 流量排空：标记所有实例为 dying，等待存量请求完成
        drainInstances(lingId, runtime.getInstancePool().getActiveInstances(),
                runtime.getConfig().getForceCleanupDelaySeconds());

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
        instanceCoordinator.tearDown(targetInstance, eventBus);
        runtime.getInstancePool().removeInstance(targetInstance);

        for (ResourceGuard guard : resourceGuards) {
            try {
                guard.cleanup(lingId, classLoader);
            } catch (Exception e) {
                log.error("[{}] Resource cleanup failed for version {} with guard: {}", lingId, version,
                        guard.getClass().getName(), e);
            }
        }

        if (classLoader instanceof AutoCloseable) {
            try {
                ((AutoCloseable) classLoader).close();
            } catch (Exception e) {
                log.error("[{}] Failed to close ClassLoader for version {}", lingId, version, e);
            }
        }

        for (ResourceGuard guard : resourceGuards) {
            try {
                guard.detectLeak(lingId, classLoader);
            } catch (Exception e) {
                log.error("[{}] Leak detection failed for version {} with guard: {}", lingId, version,
                        guard.getClass().getName(), e);
            }
        }

        // 4. 全局状态检查
        List<LingInstance> remaining = runtime.getInstancePool().getAllInstances();
        if (remaining.isEmpty()) {
            log.info("[{}] No instances remaining after version {} unloaded. Cleaning up runtime.", lingId, version);
            // 触发全量清场（不包含已经卸载的实例）
            doFullUndeploy(lingId, runtime);
        } else {
            log.info("[{}] Ling has {} instances remaining, skipping runtime cleanup.", lingId, remaining.size());
        }
    }

    private void doFullUndeploy(String lingId, LingRuntime runtime) {
        eventBus.publish(new LingUninstallingEvent(lingId));

        // 1. 改变宏观状态，拒绝新请求
        if (runtime.getStateMachine().current() != RuntimeStatus.STOPPING) {
            runtime.getStateMachine().transition(RuntimeStatus.STOPPING);
        }

        // 2. 逐个卸载底层剩余的所有实例
        List<LingInstance> instances = runtime.getInstancePool().getAllInstances();
        for (LingInstance instance : instances) {
            // 🔥 先获取 ClassLoader，再 tearDown
            ClassLoader classLoader = instance.getContainer().getClassLoader();

            instanceCoordinator.tearDown(instance, eventBus);
            runtime.getInstancePool().removeInstance(instance);

            // 🔥 彻底卸载的关键：清理资源并检测泄漏
            for (ResourceGuard guard : resourceGuards) {
                try {
                    guard.cleanup(lingId, classLoader);
                } catch (Exception e) {
                    log.error("[{}] Resource cleanup failed with guard: {}", lingId, guard.getClass().getName(), e);
                }
            }

            // 显式关闭类加载器 (释放 Jar 句柄)
            if (classLoader instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) classLoader).close();
                } catch (Exception e) {
                    log.error("[{}] Failed to close ClassLoader", lingId, e);
                }
            }

            // 延迟触发泄漏检测
            for (ResourceGuard guard : resourceGuards) {
                try {
                    guard.detectLeak(lingId, classLoader);
                } catch (Exception e) {
                    log.error("[{}] Leak detection failed with guard: {}", lingId, guard.getClass().getName(), e);
                }
            }
        }

        // 3. 清理注册表中的暴露条目
        lingServiceRegistry.evict(lingId);

        // 4. 驱逐弹性治理组件（限流器、熔断器），防止内存泄漏
        pipelineEngine.evictLingResources(lingId);

        // 5. 彻底解绑监听与权限
        eventBus.unsubscribeAll(lingId);
        permissionService.removeLing(lingId);

        // 6. 宣告生命终结
        runtime.getStateMachine().transition(RuntimeStatus.REMOVED);

        // 7. 从仓储拔除引用
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

        if (classLoader != null && !resourceGuards.isEmpty()) {
            for (ResourceGuard guard : resourceGuards) {
                try {
                    guard.cleanup("fault-cleanup", classLoader);
                } catch (Exception e) {
                    log.warn("ResourceGuard cleanup failed during failure recovery with guard: {}",
                            guard.getClass().getName(), e);
                }
            }
        }

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
     * 基础设施依赖：
     * - {@link LingInstance#markDying()} → 将实例状态置为 STOPPING，{@code tryEnter()} 后续返回
     * false
     * - {@link LingInstance#isIdle()} → {@code activeRequests.get() == 0}
     * <p>
     * 超时保护：超过 {@code timeoutSeconds} 后强制继续，避免无限等待。
     *
     * @param lingId         灵元 ID（仅用于日志）
     * @param instances      需要排空的实例列表
     * @param timeoutSeconds 排空超时秒数（来自
     *                       {@link LingRuntimeConfig#getForceCleanupDelaySeconds()}）
     */
    private void drainInstances(String lingId, List<LingInstance> instances, int timeoutSeconds) {
        if (instances == null || instances.isEmpty()) {
            return;
        }

        // 第一步：标记所有实例为 dying，拒绝新请求
        for (LingInstance instance : instances) {
            if (!instance.isDying()) {
                try {
                    instance.markDying();
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
