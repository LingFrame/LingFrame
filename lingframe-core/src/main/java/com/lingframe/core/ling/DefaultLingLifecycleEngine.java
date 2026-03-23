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
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.resource.DefaultLeakDetector;
import com.lingframe.core.security.ApiOverrideVerifier;
import com.lingframe.core.security.DangerousApiVerifier;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.spi.LingLoaderFactory;
import com.lingframe.core.spi.LingSecurityVerifier;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 顶层生命周期编排器。
 * <p>
 * 它负责把部署与卸载意图翻译为具体的运行时阶段与实例阶段，
 * 并负责这些阶段的执行顺序；
 * 但真正的状态写入仍然委托给 {@link RuntimeCoordinator} 与
 * {@link InstanceCoordinator}。
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
                lingRepository, lingServiceRegistry, pipelineEngine, lingResourceManager,
                unloadCoordinator, runtimeCoordinator, null);
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
                : new LingUnloadCoordinator(
                        pipelineEngine,
                        new ArrayList<>(),
                        lingResourceManager,
                        new DefaultLeakDetector(eventBus, lingFrameConfig));
        this.hotSwapWatcher = hotSwapWatcher;
        this.instanceCoordinator = new InstanceCoordinator(eventBus);
        this.runtimeCoordinator = Objects.requireNonNull(runtimeCoordinator, "RuntimeCoordinator is required");
    }

    public void setHotSwapWatcher(HotSwapWatcher hotSwapWatcher) {
        this.hotSwapWatcher = hotSwapWatcher;
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
            isNewRuntime = validateDeploymentRequest(lingId, version, sourceFile, allowSameVersion);
            lingClassLoader = lingLoaderFactory.create(lingId, sourceFile, getClass().getClassLoader());
            container = containerFactory.create(lingDefinition, sourceFile, lingClassLoader);

            LingInstance instance = createDeploymentInstance(container, lingDefinition, labels);
            LingRuntime runtime = ensureRuntimeForDeployment(lingId);

            driveInstanceToLoading(instance);
            startPreparedInstance(instance, createLingContext(lingId));
            registerHotSwapIfNeeded(lingId, sourceFile, lingDefinition);
            publishReadyInstance(runtime, instance, isDefault);
            grantDeclaredPermissions(lingId, lingDefinition);

            eventBus.publish(new LingInstalledEvent(lingId, version));
            log.info("[{}] Installed successfully", lingId);
        } catch (Throwable t) {
            log.error("Failed to install ling: {} v{}", lingId, version, t);
            rollbackNewRuntimeRegistration(lingId, isNewRuntime);
            cleanupOnFailure(lingClassLoader, container);
            throw t;
        }
    }

    @Override
    public void undeploy(String lingId) {
        log.info("Uninstalling ling: {}", lingId);

        LingRuntime runtime = findRuntimeOrWarn(lingId);
        if (runtime == null) {
            return;
        }

        drainInstances(lingId, runtime.getInstancePool().getActiveInstances(),
                runtime.getConfig().getForceCleanupDelaySeconds());
        doFullUndeploy(lingId, runtime);
    }

    @Override
    public void undeploy(String lingId, String version) {
        log.info("Uninstalling ling: {} version: {}", lingId, version);

        LingRuntime runtime = findRuntimeOrWarn(lingId);
        if (runtime == null) {
            return;
        }

        LingInstance targetInstance = findActiveInstanceOrWarn(lingId, runtime, version);
        if (targetInstance == null) {
            return;
        }

        undeploySelectedInstance(lingId, runtime, targetInstance);
    }

    @Override
    public void undeploy(String lingId, LingInstance instance) {
        if (instance == null) {
            return;
        }

        LingRuntime runtime = findRuntimeOrWarn(lingId);
        if (runtime == null) {
            return;
        }

        log.info("Uninstalling ling: {} instance version: {}", lingId, instance.getVersion());
        if (!runtime.getInstancePool().getAllInstances().contains(instance)) {
            log.warn("Ling instance not found or already removed for: {}:{}", lingId, instance.getVersion());
            return;
        }

        undeploySelectedInstance(lingId, runtime, instance);
    }

    private boolean validateDeploymentRequest(String lingId, String version, File sourceFile, boolean allowSameVersion) {
        verifySecurity(lingId, sourceFile);
        return ensureVersionCanBeDeployed(lingId, version, allowSameVersion);
    }

    private void verifySecurity(String lingId, File sourceFile) {
        for (LingSecurityVerifier verifier : verifiers) {
            verifier.verify(lingId, sourceFile);
        }
    }

    private boolean ensureVersionCanBeDeployed(String lingId, String version, boolean allowSameVersion) {
        if (!lingRepository.hasRuntime(lingId)) {
            return true;
        }

        log.info("[{}] Preparing for upgrade", lingId);
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (!allowSameVersion && runtime != null && runtime.getInstancePool().getInstance(version) != null) {
            throw new IllegalStateException("Version " + version + " is already deployed for ling " + lingId
                    + ". Please uninstall it first.");
        }
        return false;
    }

    private LingInstance createDeploymentInstance(LingContainer container, LingDefinition lingDefinition,
                                                  Map<String, String> labels) {
        LingDefinition instanceDefinition = lingDefinition.copy();
        LingInstance instance = new LingInstance(container, instanceDefinition, eventBus);
        instance.addLabels(labels);
        return instance;
    }

    private LingRuntime ensureRuntimeForDeployment(String lingId) {
        // 先注册运行时聚合器，再允许实例事件出现。
        // 否则首个 LOADING / STARTING 事件会缺少宏观状态落点。
        runtimeCoordinator.register(lingId);

        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            LingRuntimeConfig runtimeConfig = lingFrameConfig != null ? lingFrameConfig.getRuntimeConfig() : null;
            runtime = new LingRuntime(lingId, runtimeConfig, eventBus, instanceCoordinator, runtimeCoordinator);
            lingRepository.register(runtime);
        }
        return runtime;
    }

    private void driveInstanceToLoading(LingInstance instance) {
        instanceCoordinator.prepare(instance);
    }

    private LingContext createLingContext(String lingId) {
        return new DefaultLingContext(
                lingId,
                lingRepository,
                lingServiceRegistry,
                pipelineEngine,
                permissionService,
                eventBus);
    }

    private void startPreparedInstance(LingInstance instance, LingContext context) {
        instanceCoordinator.start(instance);
        instance.getContainer().start(context);
    }

    private void registerHotSwapIfNeeded(String lingId, File sourceFile, LingDefinition lingDefinition) {
        if (hotSwapWatcher != null
                && lingFrameConfig != null
                && lingFrameConfig.isDevMode()
                && sourceFile != null
                && sourceFile.isDirectory()) {
            hotSwapWatcher.register(lingId, sourceFile, lingDefinition);
        }
    }

    private void publishReadyInstance(LingRuntime runtime, LingInstance instance, boolean isDefault) {
        // 先提交到实例池，再发布 READY 事实。
        // 这样一旦 RuntimeCoordinator 因 READY 聚合出 ACTIVE，
        // 运行时侧的成员视图已经能看到这个实例，避免“状态先变绿、成员还没挂上”的瞬时割裂。
        runtime.getInstancePool().addInstance(instance, isDefault);

        // READY 事实向上游汇报，RuntimeCoordinator 再基于事件推导宏观状态。
        instanceCoordinator.markReady(instance);
    }

    private void grantDeclaredPermissions(String lingId, LingDefinition lingDefinition) {
        if (lingDefinition.getGovernance() == null
                || lingDefinition.getGovernance().getCapabilities() == null) {
            return;
        }

        for (GovernancePolicy.CapabilityRule rule : lingDefinition.getGovernance().getCapabilities()) {
            try {
                AccessType accessType = AccessType.valueOf(rule.getAccessType().toUpperCase());
                permissionService.grant(lingId, rule.getCapability(), accessType);
            } catch (IllegalArgumentException e) {
                log.warn("[{}] Invalid access type: {}", lingId, rule.getAccessType());
            }
        }
    }

    private void rollbackNewRuntimeRegistration(String lingId, boolean isNewRuntime) {
        if (isNewRuntime) {
            lingRepository.deregister(lingId);
        }
    }

    private LingRuntime findRuntimeOrWarn(String lingId) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            log.warn("Ling not found: {}", lingId);
        }
        return runtime;
    }

    private LingInstance findActiveInstanceOrWarn(String lingId, LingRuntime runtime, String version) {
        LingInstance targetInstance = runtime.getInstancePool().getInstance(version);
        if (targetInstance == null) {
            log.warn("Ling instance not found or already dying for: {}:{}", lingId, version);
        }
        return targetInstance;
    }

    private void undeploySelectedInstance(String lingId, LingRuntime runtime, LingInstance targetInstance) {
        drainInstances(lingId, Collections.singletonList(targetInstance),
                runtime.getConfig().getForceCleanupDelaySeconds());
        unloadSingleInstance(lingId, runtime, targetInstance);
        finalizeRuntimeRemovalIfEmpty(lingId, runtime, targetInstance.getVersion());
    }

    private void unloadSingleInstance(String lingId, LingRuntime runtime, LingInstance instance) {
        String version = instance.getVersion();
        ClassLoader classLoader = instance.getClassLoader();

        instanceCoordinator.tearDown(instance);
        runtime.getInstancePool().removeInstance(instance);

        unloadCoordinator.onVersionUnload(lingId, version, classLoader);
        closeClassLoader(lingId, version, classLoader);
        unloadCoordinator.detectLeak(lingId, version, classLoader);
    }

    private void finalizeRuntimeRemovalIfEmpty(String lingId, LingRuntime runtime, String version) {
        List<LingInstance> remaining = runtime.getInstancePool().getAllInstances();
        if (remaining.isEmpty()) {
            log.info("[{}] No instances remaining after version {} unloaded. Cleaning up runtime.",
                    lingId, version);
            doFullUndeploy(lingId, runtime);
        } else {
            log.info("[{}] Ling has {} instances remaining, skipping runtime cleanup.", lingId, remaining.size());
        }
    }

    private void doFullUndeploy(String lingId, LingRuntime runtime) {
        eventBus.publish(new LingUninstallingEvent(lingId));
        unregisterHotSwapWatcher(lingId);
        enterRuntimeStopping(lingId, runtime);
        unloadAllInstances(lingId, runtime);
        clearServiceRegistry(lingId);
        finalizeLingRemoval(lingId);
        runtimeCoordinator.purge(lingId);
        lingRepository.deregister(lingId);
        eventBus.publish(new LingUninstalledEvent(lingId));
    }

    private void unregisterHotSwapWatcher(String lingId) {
        if (hotSwapWatcher != null) {
            hotSwapWatcher.unregister(lingId);
        }
    }

    private void enterRuntimeStopping(String lingId, LingRuntime runtime) {
        RuntimeStatus current = runtime.currentStatus();
        if (current != RuntimeStatus.STOPPING
                && current != RuntimeStatus.INACTIVE
                && current != RuntimeStatus.REMOVED) {
            runtimeCoordinator.shutdown(lingId);
        }
    }

    private void unloadAllInstances(String lingId, LingRuntime runtime) {
        List<LingInstance> instances = new ArrayList<>(runtime.getInstancePool().getAllInstances());
        for (LingInstance instance : instances) {
            unloadSingleInstance(lingId, runtime, instance);
        }
    }

    private void clearServiceRegistry(String lingId) {
        lingServiceRegistry.evict(lingId);
        List<String> remainingServices = lingServiceRegistry.getServicesByLingId(lingId);
        if (remainingServices != null && !remainingServices.isEmpty()) {
            log.warn("[{}] Service registry still has {} entries after evict, forcing cleanup",
                    lingId, remainingServices.size());
            lingServiceRegistry.evict(lingId);
        }
    }

    private void finalizeLingRemoval(String lingId) {
        unloadCoordinator.onLingUnload(lingId);
        eventBus.unsubscribeAll(lingId);
        permissionService.removeLing(lingId);
    }

    private void closeClassLoader(String lingId, String version, ClassLoader classLoader) {
        if (classLoader instanceof AutoCloseable) {
            try {
                ((AutoCloseable) classLoader).close();
            } catch (Exception e) {
                log.error("[{}] Failed to close ClassLoader for version {}", lingId, version, e);
            }
        }
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
     * 先将实例标记为 STOPPING，等待飞行中请求排空；
     * 如果达到超时时间，则继续后续卸载流程。
     */
    private void drainInstances(String lingId, List<LingInstance> instances, int timeoutSeconds) {
        if (instances == null || instances.isEmpty()) {
            return;
        }

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

        long deadlineMs = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        boolean allIdle = false;

        while (System.currentTimeMillis() < deadlineMs) {
            allIdle = true;
            for (LingInstance instance : instances) {
                if (!instance.isIdle()) {
                    allIdle = false;
                    log.debug("[{}] Waiting for instance {} to drain ({} active requests)...",
                            lingId, instance.getVersion(), instance.getActiveRequestCount());
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
            long nowMillis = System.currentTimeMillis();
            for (LingInstance instance : instances) {
                if (!instance.isIdle()) {
                    log.warn("[{}] Force proceeding: instance {} still has {} active requests",
                            lingId, instance.getVersion(), instance.getActiveRequestCount());
                    for (String summary : describeActiveInvocations(instance, nowMillis)) {
                        log.warn("[{}] In-flight invocation on instance {}: {}",
                                lingId, instance.getVersion(), summary);
                    }
                }
            }
        }
    }

    static List<String> describeActiveInvocations(LingInstance instance, long nowMillis) {
        if (instance == null) {
            return Collections.emptyList();
        }
        List<ActiveInvocationSnapshot> snapshots = instance.snapshotActiveInvocations();
        if (snapshots.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> summaries = new ArrayList<>(snapshots.size());
        for (ActiveInvocationSnapshot snapshot : snapshots) {
            summaries.add(snapshot.toSummary(nowMillis));
        }
        return summaries;
    }
}
