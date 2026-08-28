package com.lingframe.core.ling;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.context.LingContext;
import com.lingframe.api.event.lifecycle.LingInstalledEvent;
import com.lingframe.api.event.lifecycle.LingInstallingEvent;
import com.lingframe.api.event.lifecycle.LingUninstalledEvent;
import com.lingframe.api.event.lifecycle.LingUninstallingEvent;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.api.exception.RoutingArchitectureViolationException;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.context.DefaultLingContext;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.InstanceStatus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.routing.MigrationPhase;
import com.lingframe.core.routing.MigrationStateHolder;
import com.lingframe.core.routing.ProviderDescriptor;
import com.lingframe.core.spi.LeakDetector;
import com.lingframe.core.spi.LeakRiskReport;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.core.spi.LingLoaderFactory;
import com.lingframe.core.spi.RoutableTarget;
import com.lingframe.core.spi.LingSecurityVerifier;
import com.lingframe.core.spi.LingMetricsCollector;
import com.lingframe.core.spi.LingGovernanceMetricsCollector;
import com.lingframe.core.spi.LingAlertManager;
import com.lingframe.core.spi.LingHotSwapWatcher;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;


/**
 * 顶层生命周期编排器。
 * <p>
 * 它负责把部署与卸载意图翻译为具体的运行时阶段与实例阶段，
 * 并负责这些阶段的执行顺序；
 * 但真正的状态写入仍然委托给 {@link RuntimeCoordinator} 与
 * {@link InstanceCoordinator}。
 */
@Slf4j
public class DefaultLingLifecycleEngine implements LingFrameRuntime {

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
    private LingHotSwapWatcher hotSwapWatcher;
    private MigrationStateHolder migrationStateHolder;

    // 资源托管：注入 LingResourceManager 供 createLingContext 装配 registerCloseable，
    // 并用于安装失败回滚收敛孤儿资源清理。可为 null（老装配/测试缺失时降级为不托管）。
    private final LingResourceManager lingResourceManager;

    private final InstanceCoordinator instanceCoordinator;
    private final RuntimeCoordinator runtimeCoordinator;

    private LingMetricsCollector metricsCollector;
    private LingGovernanceMetricsCollector governanceMetricsCollector;
    private LingAlertManager alertManager;
    private LeakDetector leakDetector;

    private final long lifecycleLockTimeoutMs;
    private final Map<String, LockWrapper> lifecycleLocks = new ConcurrentHashMap<>();

    /**
     * 全量卸载的「卸载完成」通知登记表：lingId → 待收的泄漏检测计数。
     * <p>
     * LingUninstalledEvent 不再在 doFullUndeploy 末尾同步发布，而是等泄漏检测
     * （{@link MonitoringEvents.LeakDetectionEvent}，GC 回收验证结论）到齐后再由
     * {@link #onLeakDetection} 发布 —— 保证前端列表刷新发生在 ClassLoader 回收验证之后。
     * 热替换/版本卸载的检测事件（lingId 未登记）会被忽略，不会误发卸载完成事件。
     */
    private final Map<String, PendingUninstallNotification> pendingUninstallNotifications = new ConcurrentHashMap<>();

    /** 卸载完成通知的兜底时限：异常场景（如 null ClassLoader 未触发检测）下超时仍发事件，保证列表刷新不丢失。 */
    private static final long UNINSTALL_NOTIFY_FALLBACK_MS = 30_000L;

    /**
     * 卸载完成通知兜底的共享调度器。
     * <p>
     * 使用单线程调度器而非每次卸载 new Thread：全量卸载频率低、任务有 30s 固定延时，
     * 共享调度器避免高频卸载场景下反复创建线程的开销。
     * <p>
     * ⚠️ 线程生命周期：必须允许核心线程空闲超时回收（{@code allowCoreThreadTimeOut} + keepAlive 60s）。
     * 本引擎是 core 内核类、无显式 close/destroy 生命周期入口，若核心线程常驻，
     * 调度器会通过任务闭包长期持有引擎实例与 eventBus 引用链，导致引擎无法被 GC
     * （每新建一个引擎实例就泄漏一个常驻线程）。空闲 60s 后线程自动退出，
     * 引擎被回收后调度器随之消亡，无长生命周期持有。
     */
    private final ScheduledExecutorService uninstallNotifyScheduler = createUninstallNotifyScheduler();

    private static ScheduledExecutorService createUninstallNotifyScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "ling-uninstall-notify-fallback");
            t.setDaemon(true);
            return t;
        });
        // 核心线程空闲 60s 自动退出，避免常驻线程泄漏（见字段注释）
        executor.setKeepAliveTime(60, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        // 已取消的延迟任务立即出队，不残留
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static final class PendingUninstallNotification {
        final AtomicInteger remaining;
        volatile boolean leaked;

        PendingUninstallNotification(int expectedDetections) {
            this.remaining = new AtomicInteger(Math.max(1, expectedDetections));
        }
    }

    private static class LockWrapper {
        final ReentrantLock lock = new ReentrantLock();
        final AtomicInteger holdCount = new AtomicInteger(0);
    }

    public DefaultLingLifecycleEngine(LifecycleEngineConfig config) {
        Objects.requireNonNull(config, "LifecycleEngineConfig is required");
        this.containerFactory = Objects.requireNonNull(config.getContainerFactory(), "containerFactory is required");
        this.permissionService = Objects.requireNonNull(config.getPermissionService(), "permissionService is required");
        this.lingLoaderFactory = Objects.requireNonNull(config.getLingLoaderFactory(), "lingLoaderFactory is required");
        this.eventBus = Objects.requireNonNull(config.getEventBus(), "eventBus is required");
        this.lingFrameConfig = Objects.requireNonNull(config.getLingFrameConfig(), "lingFrameConfig is required");
        this.lingRepository = Objects.requireNonNull(config.getLingRepository(), "lingRepository is required");
        this.lingServiceRegistry = Objects.requireNonNull(config.getLingServiceRegistry(), "lingServiceRegistry is required");
        this.pipelineEngine = Objects.requireNonNull(config.getPipelineEngine(), "pipelineEngine is required");
        this.runtimeCoordinator = Objects.requireNonNull(config.getRuntimeCoordinator(), "runtimeCoordinator is required");

        // 微内核解耦：安全验证器由外部组装点注入，内核不关心具体类型
        List<LingSecurityVerifier> verifiers = config.getVerifiers();
        this.verifiers = verifiers != null ? new ArrayList<>(verifiers) : new ArrayList<>();

        // unloadCoordinator 必传：内核不再兜底创建，装配层负责构造完整协调器
        this.unloadCoordinator = Objects.requireNonNull(config.getUnloadCoordinator(),
                "unloadCoordinator is required (assemble a complete LingUnloadCoordinator at the wiring layer)");

        // 资源管理器为可选依赖（老装配可缺失），缺失时 createLingContext 降级为不托管孤儿资源
        this.lingResourceManager = config.getLingResourceManager();

        this.hotSwapWatcher = config.getHotSwapWatcher();
        this.migrationStateHolder = config.getMigrationStateHolder();
        this.metricsCollector = config.getMetricsCollector();
        this.governanceMetricsCollector = config.getGovernanceMetricsCollector();
        this.alertManager = config.getAlertManager();
        this.instanceCoordinator = new InstanceCoordinator(eventBus);

        // leakDetector：优先用 Builder 显式注入的，否则从 unloadCoordinator 派生
        LeakDetector explicitLeakDetector = config.getLeakDetector();
        this.leakDetector = explicitLeakDetector != null ? explicitLeakDetector : this.unloadCoordinator.getLeakDetector();

        this.lifecycleLockTimeoutMs = config.getLifecycleLockTimeoutMs() > 0
                ? config.getLifecycleLockTimeoutMs()
                : 120_000L;

        // 全局监听泄漏检测结论：全量卸载时据此在验证完成后发布 LingUninstalledEvent（事件换位置，公共契约零变更）
        eventBus.subscribeGlobal(MonitoringEvents.LeakDetectionEvent.class, this::onLeakDetection);
    }

    @Override
    public <T> T withLifecycleLock(String lingId, Callable<T> action) {
        if (lingId == null || lingId.trim().isEmpty()) {
            throw new IllegalArgumentException("lingId must not be null or blank");
        }
        Objects.requireNonNull(action, "action must not be null");
        ReentrantLock lock = acquireLifecycleLock(lingId);
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
            releaseLifecycleLock(lingId);
        }
    }

    private ReentrantLock acquireLifecycleLock(String lingId) {
        LockWrapper wrapper = lifecycleLocks.compute(lingId, (k, oldVal) -> {
            if (oldVal == null) {
                oldVal = new LockWrapper();
            }
            oldVal.holdCount.incrementAndGet();
            return oldVal;
        });
        try {
            if (!wrapper.lock.tryLock(lifecycleLockTimeoutMs, TimeUnit.MILLISECONDS)) {
                releaseLifecycleLock(lingId);
                throw new IllegalStateException("Acquire lifecycle lock timeout: lingId=" + lingId
                        + ", timeout=" + lifecycleLockTimeoutMs + "ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            releaseLifecycleLock(lingId);
            throw new IllegalStateException("Acquire lifecycle lock interrupted: lingId=" + lingId, e);
        }
        return wrapper.lock;
    }

    /**
     * 释放一次锁持有计数；计数归零时移除该 ling 的锁条目，防止 LockWrapper 内存残留。
     * 幂等：条目不存在（已移除）时为 no-op。
     */
    private void releaseLifecycleLock(String lingId) {
        lifecycleLocks.computeIfPresent(lingId, (k, val) -> {
            if (val.holdCount.decrementAndGet() <= 0) {
                return null;
            }
            return val;
        });
    }

    @Override
    public LingRepository getRepository() {
        return lingRepository;
    }

    @Override
    public LingServiceRegistry getServiceRegistry() {
        return lingServiceRegistry;
    }

    @Override
    public InvocationPipelineEngine getPipelineEngine() {
        return pipelineEngine;
    }

    @Override
    public EventBus getEventBus() {
        return eventBus;
    }

    @Override
    public PermissionService getPermissionService() {
        return permissionService;
    }

    @Override
    public MigrationStateHolder getMigrationStateHolder() {
        return migrationStateHolder;
    }

    @Override
    public Optional<LingMetricsCollector> getMetricsCollector() {
        return Optional.ofNullable(metricsCollector);
    }

    @Override
    public Optional<LingGovernanceMetricsCollector> getGovernanceMetricsCollector() {
        return Optional.ofNullable(governanceMetricsCollector);
    }

    @Override
    public Optional<LingAlertManager> getAlertManager() {
        return Optional.ofNullable(alertManager);
    }

    @Override
    public Optional<LeakDetector> getLeakDetector() {
        return Optional.ofNullable(leakDetector);
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
        Objects.requireNonNull(lingDefinition, "lingDefinition is required");
        lingDefinition.validate();
        String lingId = lingDefinition.getId();
        withLifecycleLock(lingId, () -> {
            doDeployInternal(lingDefinition, sourceFile, isDefault, labels, allowSameVersion);
            return null;
        });
    }

    private void doDeployInternal(LingDefinition lingDefinition, File sourceFile, boolean isDefault,
                                  Map<String, String> labels, boolean allowSameVersion) {
        String lingId = lingDefinition.getId();
        String version = lingDefinition.getVersion();
        eventBus.publish(new LingInstallingEvent(lingId, version, sourceFile));

        // 前置独占检查：禁止叠加迁移/迭代
        assertExclusiveForDeployment(lingId);

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
            startPreparedInstance(instance, createLingContext(instance));
            registerHotSwapIfNeeded(lingId, sourceFile, lingDefinition);
            publishReadyInstance(runtime, instance, isDefault);
            // 仅首次部署时授予声明权限；reload 场景保留用户动态修改的权限，避免被重置
            if (isNewRuntime) {
                grantDeclaredPermissions(lingId, lingDefinition);
            }

            eventBus.publish(new LingInstalledEvent(lingId, version));
            log.info("[{}] Installed successfully", lingId);
        } catch (Error e) {
            // Error（OOM / StackOverflow）代表 JVM 即将崩溃，跳过回滚副作用直接透传，
            // 避免回滚路径再次 OOM 掩盖原始崩溃事实。
            log.error("Failed to install ling (Error): {} v{}", lingId, version, e);
            throw e;
        } catch (Throwable t) {
            log.error("Failed to install ling: {} v{}", lingId, version, t);
            rollbackNewRuntimeRegistration(lingId, isNewRuntime);
            // 透传 lingId/version：onStart 内已注册的孤儿资源随回滚释放，避免泄漏
            cleanupOnFailure(lingId, version, lingClassLoader, container);
            throw t;
        }
    }

    @Override
    public void undeploy(String lingId) {
        undeployWithReport(lingId);
    }

    @Override
    public void recover(String lingId, String version) {
        if (lingId == null || lingId.trim().isEmpty()) {
            throw new IllegalArgumentException("lingId must not be null or blank");
        }
        withLifecycleLock(lingId, () -> {
            doRecover(lingId, version);
            return null;
        });
    }

    private void doRecover(String lingId, String version) {
        LingRuntime runtime = findRuntimeOrWarn(lingId);
        if (runtime == null) {
            throw new IllegalStateException("Ling not found: " + lingId);
        }

        LingInstance targetInstance = version == null
                ? selectRecoverableInstance(runtime)
                : findActiveInstanceOrWarn(lingId, runtime, version);
        if (targetInstance == null) {
            throw new IllegalStateException("No recoverable instance found for ling " + lingId);
        }

        runtimeCoordinator.transition(lingId, RuntimeStatus.RECOVERING);
        try {
            if (pipelineEngine != null) {
                pipelineEngine.recoverLingGovernance(lingId);
            }

            if (targetInstance.currentStatus() == InstanceStatus.ERROR) {
                recoverErroredInstance(lingId, targetInstance);
                return;
            }

            if (runtime.getInstancePool().hasAvailableInstance()) {
                runtimeCoordinator.transition(lingId, RuntimeStatus.ACTIVE);
                log.info("[{}] Runtime governance state recovered without instance restart", lingId);
                return;
            }

            runtimeCoordinator.transition(lingId, RuntimeStatus.DEGRADED);
            throw new IllegalStateException("Runtime recovery did not find any READY instance for ling " + lingId);
        } catch (Error e) {
            // Error（OOM / StackOverflow）跳过状态降级副作用直接透传，
            // 避免在 JVM 即将崩溃时再触发协调器调用导致二次错误（与 recoverErroredInstance 的设计一致）。
            log.error("[{}] Recovery failed (Error)", lingId, e);
            throw e;
        } catch (RuntimeException e) {
            // 恢复失败兜底：显式收口到 DEGRADED，避免 runtime 卡死在 RECOVERING 意图态。
            // RECOVERING 压制聚合评估（suppressesEvaluation），MacroStateGuardFilter 会拒绝其全部流量，
            // 若失败路径不收口，灵元将永久流量阻断直到人工干预。
            // 幂等：recoverErroredInstance 内部失败时已收口 DEGRADED，此处仅在仍处于 RECOVERING 时再收口。
            if (runtimeCoordinator.getStatus(lingId) == RuntimeStatus.RECOVERING) {
                runtimeCoordinator.transition(lingId, RuntimeStatus.DEGRADED);
                log.warn("[{}] Recovery failed, runtime state forced to DEGRADED (was RECOVERING)", lingId);
            }
            throw e;
        }
    }

    @Override
    public LingUninstallResult undeployWithReport(String lingId) {
        if (lingId == null || lingId.trim().isEmpty()) {
            return LingUninstallResult.notTriggered(lingId, null, Collections.emptyList());
        }
        return withLifecycleLock(lingId, () -> doUndeployWithReport(lingId));
    }

    private LingUninstallResult doUndeployWithReport(String lingId) {
        // 灵核判断改为类型判断：灵核不是 LingRuntime（是 LingCoreRoutableTarget），
        // getRuntime 返回 null，因此「不是 LingRuntime」即「不可卸载」。
        // 灵核不进 RuntimeCoordinator.machines，shutdown/transition 在 fsm == null 时直接拒绝。
        // 类型判断让能力成为类型的派生属性。
        if (lingRepository.getRuntime(lingId) == null) {
            // 区分两种情况：灵核（RoutableTarget 存在但不是 LingRuntime）和 真正不存在的 lingId
            if (lingRepository.getRoutableTarget(lingId) != null) {
                log.warn("[{}] Undeploy rejected: target is not LingRuntime (ling core)", lingId);
            }
            return LingUninstallResult.notTriggered(lingId, null, Collections.emptyList());
        }
        log.info("Uninstalling ling: {}", lingId);

        LingRuntime runtime = findRuntimeOrWarn(lingId);
        if (runtime == null) {
            return LingUninstallResult.notTriggered(lingId, null, Collections.emptyList());
        }

        List<LeakRiskReport> reports = unloadCoordinator.checkBeforeLingUnload(
                lingId,
                new ArrayList<>(runtime.getInstancePool().getAllInstances()));

        // 先将所有活跃实例移入 dying 队列，移出 activePool。
        // 这样 STOPPING 实例不再影响 hasAvailableInstance，且剩余版本（如有）仍可服务。
        List<LingInstance> activeInstances = new ArrayList<>(runtime.getInstancePool().getActiveInstances());
        for (LingInstance instance : activeInstances) {
            runtime.getInstancePool().moveToDying(instance);
        }

        // 在排空前先进入 STOPPING 意图态（C3）：moveToDying 后实例已 STOPPING，
        // drain 窗口内 Runtime 宏观状态应呈现 STOPPING 而非 ACTIVE，避免「实例停摆 / 宏观 ACTIVE」失真；
        // 后续 doFullUndeploy 内的 enterRuntimeStopping 幂等（已是 STOPPING 则跳过）。
        enterRuntimeStopping(lingId, runtime);

        drainInstances(lingId, activeInstances,
                runtime.getConfig().getForceCleanupDelaySeconds(),
                runtime.getConfig().getDrainPollIntervalMs(),
                runtime.getConfig().isForceDrainOnTimeout());
        doFullUndeploy(lingId, runtime);
        return LingUninstallResult.triggered(lingId, null, reports);
    }

    @Override
    public void undeploy(String lingId, String version) {
        undeployWithReport(lingId, version);
    }

    @Override
    public LingUninstallResult undeployWithReport(String lingId, String version) {
        if (lingId == null || lingId.trim().isEmpty()) {
            return LingUninstallResult.notTriggered(lingId, version, Collections.emptyList());
        }
        return withLifecycleLock(lingId, () -> doUndeployWithReport(lingId, version));
    }

    private LingUninstallResult doUndeployWithReport(String lingId, String version) {
        // 灵核判断改为类型判断（同无版本重载）
        if (lingRepository.getRuntime(lingId) == null) {
            if (lingRepository.getRoutableTarget(lingId) != null) {
                log.warn("[{}] Undeploy rejected: target is not LingRuntime (ling core)", lingId);
            }
            return LingUninstallResult.notTriggered(lingId, version, Collections.emptyList());
        }
        log.info("Uninstalling ling: {} version: {}", lingId, version);

        LingRuntime runtime = findRuntimeOrWarn(lingId);
        if (runtime == null) {
            return LingUninstallResult.notTriggered(lingId, version, Collections.emptyList());
        }

        LingInstance targetInstance = findActiveInstanceOrWarn(lingId, runtime, version);
        if (targetInstance == null) {
            return LingUninstallResult.notTriggered(lingId, version, Collections.emptyList());
        }

        LeakRiskReport report = unloadCoordinator.checkBeforeVersionUnload(
                lingId,
                targetInstance.getVersion(),
                targetInstance.getClassLoader());
        undeploySelectedInstance(lingId, runtime, targetInstance);
        return LingUninstallResult.triggered(lingId, version, Collections.singletonList(report));
    }

    @Override
    public void undeploy(String lingId, LingInstance instance) {
        if (instance == null || lingId == null || lingId.trim().isEmpty()) {
            return;
        }
        withLifecycleLock(lingId, () -> {
            doUndeploy(lingId, instance);
            return null;
        });
    }

    private void doUndeploy(String lingId, LingInstance instance) {
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

    /**
     * 装配灵核实例(lingcore-app)。
     * <p>
     * 灵核作为特殊的"永久 baseline",不支持热加载/热卸载,不持有 RuntimeCoordinator 状态机,
     * 只通过 {@link LingCoreRoutableTarget} 实现 {@link RoutableTarget} 窄接口,
     * 让 Pipeline 路由阶段能在 LingRepository 找到 lingcore-app 的路由目标。
     * <p>
     * 灵核装配路径与灵元 deploy 路径的差异：
     * <ul>
     *   <li>不创建：RuntimeCoordinator 注册 / LingRuntime / InstancePool.addInstance / transition(ACTIVE)</li>
     *   <li>对称保留：LingDefinition + LingInstance 创建 + instanceCoordinator.prepare/start/markReady 三步</li>
     *   <li>新增：创建 LingCoreRoutableTarget 并注册到 LingRepository</li>
     * </ul>
     * 灵核不进 RuntimeCoordinator，治理参数走默认值。
     *
     * @param lingId    固定 {@code LingCoreConstants.LINGCORE_LING_ID}
     * @param container 灵核 LingContainer 适配器
     * @param version   固定 {@code LingCoreConstants.LINGCORE_VERSION}
     * @return 创建好的 LingInstance
     */
    public LingInstance bootstrapLingCoreInstance(String lingId, LingContainer container, String version) {
        if (lingId == null || lingId.trim().isEmpty()) {
            throw new IllegalArgumentException("lingId must not be null or blank");
        }
        return withLifecycleLock(lingId, () -> doBootstrapLingCoreInstance(lingId, container, version));
    }

    private LingInstance doBootstrapLingCoreInstance(String lingId, LingContainer container, String version) {
        log.info("Bootstrapping ling core instance: lingId={}, version={}", lingId, version);

        // 1. 构造 LingDefinition + LingInstance(与灵元 deploy 路径对称)
        LingDefinition def = new LingDefinition();
        def.setId(lingId);
        def.setVersion(version);
        def.setProvider("lingframe-core");
        def.setDescription("Ling core application as shared API producer");
        LingInstance instance = new LingInstance(container, def, eventBus);

        // 2. 状态机推进：CREATED → LOADING → STARTING → READY
        //    灵核实例没有真实的加载/启动过程(ApplicationContext 由 Spring Boot 管理),这里快速穿越中间状态
        //    保留 InstanceCoordinator 三步：灵核 LingInstance 与灵元走相同生命周期推进
        instanceCoordinator.prepare(instance);
        instanceCoordinator.start(instance);
        instanceCoordinator.markReady(instance);

        // 3. 路由升维：创建 LingCoreRoutableTarget 注册到 LingRepository（替代原 LingRuntime 注册）
        //    灵核不进 RuntimeCoordinator，currentStatus() 永远返回 ACTIVE
        LingCoreRoutableTarget target = new LingCoreRoutableTarget(lingId, instance);
        lingRepository.registerRoutableTarget(target);

        log.info("[{}] Ling core instance bootstrapped, routable target registered", lingId);
        return instance;
    }

    private void driveInstanceToLoading(LingInstance instance) {
        instanceCoordinator.prepare(instance);
    }

    private LingContext createLingContext(LingInstance instance) {
        return new DefaultLingContext(
                instance,
                lingRepository,
                lingServiceRegistry,
                pipelineEngine,
                permissionService,
                eventBus,
                lingResourceManager);
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
        LingInstance replacedDefault = runtime.getInstancePool().addInstance(instance, isDefault);

        // 被替换的旧默认实例必须立即走濒死流程：先 stop 推进到 STOPPING，再入 dyingQueue，
        // 否则它既不在 activePool（已被原子替换）也不在 dyingQueue，成为孤儿引用，
        // 跳过排空校验且 dyingCount 统计失真。
        if (replacedDefault != null && replacedDefault != instance) {
            log.info("[{}] Replaced default instance {} -> {}, moving old one to dying queue",
                    runtime.getLingId(), replacedDefault.getVersion(), instance.getVersion());
            runtime.getInstancePool().moveToDying(replacedDefault);
            // 立即排空该池的濒死队列（含本次被替换实例）：
            // 已无在途请求（isIdle）的实例直接回收，
            // 否则反复「部署新版本顶替旧版本」会持续累积 LingClassLoader + Spring 容器，
            // 导致 Metaspace/堆无界增长。
            // 此前替换时仍在途、现已空闲的实例也会在本次排空中一并回收（级联场景兜底）。
            // 仍未空闲的实例保留在队列，等待后续部署时的再次排空或显式卸载。
            // ⚠️ 回收必须走完整卸载（tearDown + 卸载钩子 + 关闭 ClassLoader + 泄漏检测），
            // 不能只 tearDown 实例而放任 LingClassLoader 悬空：否则每次替换/重载都会
            // 泄漏一个未关闭的类加载器，且无法通过 DefaultLeakDetector 验证 GC 回收。
            runtime.getInstancePool().cleanupIdleInstances(instanceToReclaim -> {
                // 被替换实例已从实例池移除，捞出定义未被清空前先取版本快照
                String reclaimedVersion = instanceToReclaim.getVersion();
                finalizeInstanceUnload(runtime.getLingId(), instanceToReclaim);
                reconcileServiceRegistryAfterVersionUnload(
                        runtime.getLingId(), runtime, reclaimedVersion);
            });
        }

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
            // 部署失败必须同时收口 RuntimeCoordinator，避免 ghost INACTIVE 状态机残留
            runtimeCoordinator.unregister(lingId);
            lingRepository.unregister(lingId);
        }
    }

    /**
     * 前置独占检查：禁止叠加迁移/迭代。
     * <p>
     * 部署灵元时，遍历其声明提供方契约，对每个契约查 {@link MigrationStateHolder#getPhase}：
     * 非独占态（MIGRATING / ITERATING）表示已有迁移/迭代在途，禁止新部署并审计告警。
     * <p>
     * native/test 场景下 {@code migrationStateHolder} 为 null，跳过校验。
     *
     * @param lingId 待部署灵元 ID
     * @throws RoutingArchitectureViolationException 非独占态发起新部署
     */
    private void assertExclusiveForDeployment(String lingId) {
        if (migrationStateHolder == null) {
            return;
        }
        // 查灵元已声明的提供方契约（如尚未注册则无检查可做，属首次部署）
        // 此处仅对灵元已有 provider 登记的契约做独占校验，避免灵元首次部署被误拒
        Set<String> contractIds = lingServiceRegistry.getAllContractIds();
        for (String contractId : contractIds) {
            List<ProviderDescriptor> providers = lingServiceRegistry.getProvidersByContractId(contractId);
            if (providers == null || providers.isEmpty()) {
                continue;
            }
            // 只关心本灵元参与的契约
            boolean involved = false;
            for (ProviderDescriptor desc : providers) {
                if (lingId.equals(desc.getLingId())) {
                    involved = true;
                    break;
                }
            }
            if (!involved) {
                continue;
            }
            MigrationPhase phase = migrationStateHolder.getPhase(contractId);
            if (!phase.isExclusive()) {
                log.error("Deployment rejected: contract={} is in {} phase, exclusive required, ling={}",
                        contractId, phase, lingId);
                throw new RoutingArchitectureViolationException(
                        "Deployment rejected: contract=" + contractId + " is in " + phase
                                + " phase, exclusive required before new deployment, ling=" + lingId);
            }
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

    private LingInstance selectRecoverableInstance(LingRuntime runtime) {
        if (runtime == null) {
            return null;
        }
        for (LingInstance instance : runtime.getInstancePool().getAllInstances()) {
            if (instance.currentStatus() == InstanceStatus.ERROR) {
                return instance;
            }
        }
        LingInstance defaultInstance = runtime.getInstancePool().getDefault();
        if (defaultInstance != null) {
            return defaultInstance;
        }
        List<LingInstance> active = runtime.getInstancePool().getActiveInstances();
        return active.isEmpty() ? null : active.get(0);
    }

    private void recoverErroredInstance(String lingId, LingInstance instance) {
        try {
            instanceCoordinator.recovering(instance);
            startPreparedInstance(instance, createLingContext(instance));
            instanceCoordinator.markReady(instance);
            runtimeCoordinator.transition(lingId, RuntimeStatus.ACTIVE);
            log.info("[{}] Instance {} recovered successfully", lingId, instance.getVersion());
        } catch (Error e) {
            // Error（OOM / StackOverflow）跳过状态降级副作用直接透传，
            // 避免在 JVM 即将崩溃时再触发协调器调用导致二次错误。
            log.error("[{}] Failed to recover instance {} (Error)", lingId, instance.getVersion(), e);
            throw e;
        } catch (Throwable t) {
            log.error("[{}] Failed to recover instance {}", lingId, instance.getVersion(), t);
            safeTransitionToError(instance);
            runtimeCoordinator.transition(lingId, RuntimeStatus.DEGRADED);
            throw t;
        }
    }

    private void safeTransitionToError(LingInstance instance) {
        if (instance == null || instance.currentStatus() == InstanceStatus.ERROR) {
            return;
        }
        try {
            instanceCoordinator.error(instance);
        } catch (Exception e) {
            log.warn("Failed to route instance {} back to ERROR after recovery failure", instance.getVersion(), e);
        }
    }

    private void undeploySelectedInstance(String lingId, LingRuntime runtime, LingInstance targetInstance) {
        // 先将实例移入 dyingQueue，确保 drain 期间 activePool 不再包含该实例。
        // 这样并发查询（如 /lings 接口）不会看到正在卸载的旧版本，避免 reload 时出现"多版本"假象。
        // moveToDying 内部会调用 instanceCoordinator.stop() 将状态置为 STOPPING。
        runtime.getInstancePool().moveToDying(targetInstance);

        drainInstances(lingId, Collections.singletonList(targetInstance),
                runtime.getConfig().getForceCleanupDelaySeconds(),
                runtime.getConfig().getDrainPollIntervalMs(),
                runtime.getConfig().isForceDrainOnTimeout());
        unloadSingleInstance(lingId, runtime, targetInstance);
        finalizeRuntimeRemovalIfEmpty(lingId, runtime, targetInstance.getVersion());
    }

    private void unloadSingleInstance(String lingId, LingRuntime runtime, LingInstance instance) {
        // 先取版本快照再 tearDown：tearDown 后实例定义被清空，getVersion() 将回归占位值
        String version = instance.getVersion();
        finalizeInstanceUnload(lingId, instance);
        runtime.getInstancePool().removeInstance(instance);
        reconcileServiceRegistryAfterVersionUnload(lingId, runtime, version);
    }

    /**
     * 版本退役后的服务注册表精确清理。
     * <p>
     * 灵元仍存活其他版本实例时，只按版本驱逐 provider（{@code lingId:version}），
     * 保留仍在服务的其他版本；最后一版由全量卸载路径 {@link #clearServiceRegistry} 接管。
     */
    private void reconcileServiceRegistryAfterVersionUnload(String lingId, LingRuntime runtime, String version) {
        if (version == null) {
            return;
        }
        if (runtime.getInstancePool().getAllInstances().isEmpty()) {
            // 最后一版由全量卸载路径 finalizeRuntimeRemovalIfEmpty -> doFullUndeploy -> clearServiceRegistry 接管执行 evict
            return;
        }
        lingServiceRegistry.evictProvider(lingId, version);
        log.info("[{}] Version-scoped provider cleanup: version={}, other instances remain",
                lingId, version);
    }

    /**
     * 单实例完整卸载的资源回收段：tearDown 实例 + 卸载钩子 + 关闭 ClassLoader + 泄漏检测。
     * <p>
     * 注意：本方法不负责把实例从实例池移除——调用方需明确：
     * <ul>
     *   <li>显式卸载路径（{@link #unloadSingleInstance}）在回收段之外调用
     *       {@link InstancePool#removeInstance} 完成成员移除；</li>
     *   <li>{@link InstancePool#cleanupIdleInstances} 的 destroyer 回调场景由 removeIf
     *       自己从濒死队列移除，禁止在本方法内再次 removeInstance（避免并发修改）。</li>
     * </ul>
     */
    private void finalizeInstanceUnload(String lingId, LingInstance instance) {
        // 先取快照再 tearDown：tearDown 后实例定义会被清空，getVersion()/getClassLoader() 将回归占位值。
        String version = instance.getVersion();
        ClassLoader classLoader = instance.getClassLoader();
        instanceCoordinator.tearDown(instance);
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
        // 先登记「卸载完成」通知（按实例数等待泄漏检测事件到齐），再排空实例：
        // 检测是异步延迟的，登记先于事件到达，保证不漏。
        PendingUninstallNotification pending = new PendingUninstallNotification(
                runtime.getInstancePool().getAllInstances().size());
        pendingUninstallNotifications.put(lingId, pending);
        unloadAllInstances(lingId, runtime);
        clearServiceRegistry(lingId);
        finalizeLingRemoval(lingId);
        // 全量卸载必须确定性注销 coordinator：不得依赖“恰好已是 REMOVED”才能 purge
        // （实例清空后 reevaluate 常落到 INACTIVE，旧 purge 会空操作留下 ghost）
        runtimeCoordinator.unregister(lingId);
        lingRepository.unregister(lingId);
        // 事件换位置：不再在此同步发 LingUninstalledEvent；等泄漏验证（LeakDetectionEvent 到齐）完成后
        // 由 onLeakDetection 发布，保证前端列表刷新发生在 ClassLoader 回收验证之后；超时由兜底线程保证不丢失。
        scheduleUninstallNotifyFallback(lingId, pending);
    }

    /**
     * 泄漏检测结论处理：全量卸载登记的 ling 在检测到齐（GC 回收验证完成）后发布 {@link LingUninstalledEvent}。
     * <p>
     * 热替换（HotSwapWatcher）与版本卸载也会触发检测，但其 lingId 不在登记表内，直接忽略，不会误发。
     */
    private void onLeakDetection(MonitoringEvents.LeakDetectionEvent event) {
        PendingUninstallNotification pending = pendingUninstallNotifications.get(event.getLingId());
        if (pending == null) {
            return;
        }
        if (!event.isCollected()) {
            pending.leaked = true;
        }
        if (pending.remaining.decrementAndGet() <= 0) {
            pendingUninstallNotifications.remove(event.getLingId());
            eventBus.publish(new LingUninstalledEvent(event.getLingId(), pending.leaked));
        }
    }

    /**
     * 卸载完成通知的兜底：异常场景（如 null ClassLoader 未触发检测、检测被吞）下，
     * 超时后仍发布事件，保证前端列表刷新不丢失。正常路径检测事件先到，兜底线程空转退出。
     */
    private void scheduleUninstallNotifyFallback(String lingId, PendingUninstallNotification pending) {
        uninstallNotifyScheduler.schedule(() -> {
            if (pendingUninstallNotifications.get(lingId) == pending) {
                pendingUninstallNotifications.remove(lingId);
                eventBus.publish(new LingUninstalledEvent(lingId, pending.leaked));
            }
        }, UNINSTALL_NOTIFY_FALLBACK_MS, TimeUnit.MILLISECONDS);
    }

    private void unregisterHotSwapWatcher(String lingId) {
        if (hotSwapWatcher != null) {
            hotSwapWatcher.unregister(lingId);
        }
    }

    private void enterRuntimeStopping(String lingId, LingRuntime runtime) {
        RuntimeStatus current = runtime.currentStatus();
        // 排空前尽量进入 STOPPING 意图态；INACTIVE/REMOVED 由最终 unregister 统一收口
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

    @Override
    public boolean awaitUnloadCleanup(String lingId, long timeoutMs) {
        return unloadCoordinator.awaitCleanup(lingId, timeoutMs);
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

    private void cleanupOnFailure(String lingId, String version, ClassLoader classLoader, LingContainer container) {
        if (container != null) {
            try {
                container.stop();
            } catch (Exception e) {
                log.warn("Failed to stop container", e);
            }
        }

        unloadCoordinator.onFailureCleanup(lingId, version, classLoader);

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
     * <p>
     * 等待机制：使用 {@link LingInstance#awaitIdle(long)} 事件驱动等待，
     * 替代此前的 {@code Thread.sleep} 轮询。
     * <p>
     * 此前轮询的问题：
     * <ul>
     *   <li>低活跃场景：固定间隔周期性唤醒，浪费 CPU；</li>
     *   <li>高活跃场景：请求结束后最长需等待一个轮询间隔才继续卸载，延迟抖动；</li>
     *   <li>不响应外部取消：{@code Thread.sleep} 只能靠 InterruptedException 中断。</li>
     * </ul>
     * 事件驱动等待：exit() 引用计数归零时主动 signal，drain 线程在请求结束瞬间唤醒，
     * 既消除 CPU 抖动又最小化卸载延迟。{@code pollIntervalMs} 参数保留用于
     * awaitIdle 的单次等待超时（作为 deadline 兜底检查间隔），非法值兜底 50ms。
     * <p>
     * <b>多实例并发卸载场景说明</b>：本实现每轮只对首个非 idle 实例 awaitIdle，
     * 单实例卸载（reload 切换的常见场景）可达零开销事件驱动；
     * 多实例同时卸载时，若首个实例有长飞行请求，drain 线程会按 awaitSlice 粒度
     * 周期性被 deadline 唤醒重扫其他实例，效率退化为粗粒度重扫。
     * 正确性不受影响（最终仍按总 deadline 兜底），仅效率次优。
     * 罕见场景（多版本同时卸载）可接受，无需引入每实例独立等待线程的复杂度。
     */
    private void drainInstances(String lingId, List<LingInstance> instances,
                                int timeoutSeconds, int pollIntervalMs, boolean forceDrainOnTimeout) {
        if (instances == null || instances.isEmpty()) {
            return;
        }

        // 单次 awaitIdle 的等待粒度：作为 deadline 检查间隔，超时后回到外层循环重新评估总截止时间。
        // 不是 sleep 轮询间隔——awaitIdle 内部由 exit() 的 signal 唤醒，无需周期性唤醒。
        int awaitSliceMs = pollIntervalMs > 0 ? pollIntervalMs : 50;

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

        log.info("[{}] Draining {} instances, timeout={}s, awaitSlice={}ms, forceOnTimeout={}",
                lingId, instances.size(), timeoutSeconds, awaitSliceMs, forceDrainOnTimeout);

        long deadlineMs = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        boolean allIdle = false;

        // 事件驱动 drain：每轮对非 idle 实例调用 awaitIdle 阻塞等待 signal，
        // 任一实例被唤醒后重新扫描全部实例状态，全部 idle 则退出。
        while (System.currentTimeMillis() < deadlineMs) {
            LingInstance pending = findPendingInstance(lingId, instances);
            if (pending == null) {
                allIdle = true;
                break;
            }
            // 对首个非 idle 实例做事件驱动等待：exit() 归零时会 signal 唤醒此处，
            // 唤醒后回到 while 顶部重新扫描，可能其他实例也已 idle。
            // 剩余时间作为 awaitIdle 的单次超时，但不短于 awaitSliceMs 以保证语义粒度。
            long remaining = deadlineMs - System.currentTimeMillis();
            long waitMs = Math.min(awaitSliceMs, remaining);
            if (waitMs <= 0) {
                break;
            }
            try {
                pending.awaitIdle(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[{}] Drain interrupted", lingId);
                break;
            }
        }

        if (allIdle) {
            log.info("[{}] All instances drained successfully", lingId);
            return;
        }

        if (forceDrainOnTimeout) {
            int forceCount = forceProceedWithWarnings(lingId, instances);
            if (forceCount > 0 && governanceMetricsCollector != null) {
                for (LingInstance instance : instances) {
                    if (!instance.isIdle()) {
                        governanceMetricsCollector.recordForceDrain(lingId, instance.getVersion());
                    }
                }
            }
            return;
        }

        // wait-only：不允许静默打断在途请求，卸载失败由编排层感知
        int pendingCount = 0;
        long nowMillis = System.currentTimeMillis();
        for (LingInstance instance : instances) {
            if (!instance.isIdle()) {
                pendingCount++;
                if (governanceMetricsCollector != null) {
                    governanceMetricsCollector.recordDrainTimeoutAbort(lingId, instance.getVersion());
                }
                log.error("[DRAIN_TIMEOUT] [{}] instanceId={} version={} still has {} active request(s); "
                                + "forceDrainOnTimeout=false, unload aborted",
                        lingId, instance.getInstanceId(), instance.getVersion(),
                        instance.getActiveRequestCount());
                for (String summary : describeActiveInvocations(instance, nowMillis)) {
                    log.error("[DRAIN_TIMEOUT] [{}] in-flight on instanceId={}: {}",
                            lingId, instance.getInstanceId(), summary);
                }
            }
        }
        throw new IllegalStateException(String.format(
                "Drain timeout for ling [%s]: %d instance(s) still busy and forceDrainOnTimeout=false",
                lingId, pendingCount));
    }

    /**
     * 扫描实例列表，返回首个非 idle 的实例；全部 idle 则返回 null。
     */
    private LingInstance findPendingInstance(String lingId, List<LingInstance> instances) {
        for (LingInstance instance : instances) {
            if (!instance.isIdle()) {
                log.debug("[{}] Waiting for instance {} to drain ({} active requests)...",
                        lingId, instance.getVersion(), instance.getActiveRequestCount());
                return instance;
            }
        }
        return null;
    }

    /**
     * 超时后仍有活跃实例时，记录强制排空（FORCE_DRAIN）并继续卸载。
     * <p>
     * 语义：这不是静默“成功 drain”，而是可观测的强制推进。
     * 后续 tearDown 可能中断仍在飞行的请求；调用方应通过 ERROR 日志与 in-flight 摘要定位。
     *
     * @return 仍有飞行请求、被强制推进的实例数
     */
    private int forceProceedWithWarnings(String lingId, List<LingInstance> instances) {
        long nowMillis = System.currentTimeMillis();
        int forceCount = 0;
        for (LingInstance instance : instances) {
            if (!instance.isIdle()) {
                forceCount++;
                log.error("[FORCE_DRAIN] [{}] instanceId={} version={} still has {} active request(s); "
                                + "unload will proceed and may interrupt in-flight work",
                        lingId, instance.getInstanceId(), instance.getVersion(),
                        instance.getActiveRequestCount());
                for (String summary : describeActiveInvocations(instance, nowMillis)) {
                    log.error("[FORCE_DRAIN] [{}] in-flight on instanceId={}: {}",
                            lingId, instance.getInstanceId(), summary);
                }
            }
        }
        if (forceCount > 0) {
            log.error("[FORCE_DRAIN] [{}] force-proceeding unload for {} instance(s) after drain timeout",
                    lingId, forceCount);
        }
        return forceCount;
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
