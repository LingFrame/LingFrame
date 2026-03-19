package com.lingframe.core.ling;

import com.lingframe.api.context.LingContext;
import com.lingframe.api.event.LingEvent;
import com.lingframe.api.event.lifecycle.LingStartedEvent;
import com.lingframe.api.event.lifecycle.LingStartingEvent;
import com.lingframe.api.event.lifecycle.LingStoppedEvent;
import com.lingframe.api.event.lifecycle.LingStoppingEvent;
import com.lingframe.api.exception.ServiceUnavailableException;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.exception.LingInstallException;
import com.lingframe.core.spi.ResourceGuard;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.lang.ref.WeakReference;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 宿主侧实例生命周期管理器。
 * <p>
 * 引擎负责创建并预备实例，本管理器负责完成这些预备实例在宿主侧的后续阶段：
 * 启动容器、提交入池、退役被替换版本，以及销毁已经排空的实例。
 * <p>
 * 它从不直接写实例状态，所有实例状态迁移仍然统一经过 {@link InstanceCoordinator}。
 */
@Slf4j
public class LingLifecycleManager {

    private final String lingId;
    private final LingRuntimeConfig config;
    private final InstancePool instancePool;
    private final EventBus externalEventBus;
    private final ScheduledExecutorService scheduler;
    private final ResourceGuard resourceGuard;
    private final InstanceCoordinator instanceCoordinator;

    private final ReentrantLock stateLock = new ReentrantLock();
    private final AtomicBoolean forceCleanupScheduled = new AtomicBoolean(false);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public LingLifecycleManager(String lingId,
            InstancePool instancePool,
            EventBus externalEventBus,
            ScheduledExecutorService scheduler,
            LingRuntimeConfig config,
            ResourceGuard resourceGuard) {
        this.lingId = lingId;
        this.instancePool = instancePool;
        this.externalEventBus = externalEventBus;
        this.scheduler = scheduler;
        this.config = config;
        this.resourceGuard = resourceGuard;
        this.instanceCoordinator = new InstanceCoordinator(null);
        this.instancePool.setInstanceCoordinator(this.instanceCoordinator);
        schedulePeriodicCleanup();
    }

    /**
     * 将一个已经预备好的实例接入当前存活实例池。
     * <p>
     * 这里约定调用方已经创建好实例并将其推进到 STARTING，
     * 本管理器只负责宿主侧启动、进入 READY、提交入池以及退役被替换版本。
     */
    public void addInstance(LingInstance newInstance, LingContext context, boolean isDefault) {
        checkNotShutdown();

        String version = newInstance.getVersion();
        ensurePoolCanAcceptNewInstance();

        log.info("[{}] Starting new version: {}", lingId, version);
        publishStarting(version);
        startContainerAndReachReady(newInstance, context);
        commitStartedInstance(newInstance, isDefault);
        publishStarted(version);
        log.info("[{}] Version {} started", lingId, version);
    }

    /**
     * 进入关闭流程，等待死亡队列实例排空；
     * 如果在配置超时时间内仍未静止，则触发强制清理。
     */
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }

        beginShutdown();
        waitForDyingInstancesToDrain();
        shutdownManagedResources();

        log.info("[{}] Lifecycle manager shutdown complete", lingId);
    }

    public boolean isShutdown() {
        return shutdown.get();
    }

    public int cleanupIdleInstances() {
        if (stateLock.tryLock()) {
            try {
                return cleanupIdleInstancesUnderLock();
            } finally {
                stateLock.unlock();
            }
        }
        return 0;
    }

    public void forceCleanupAll() {
        log.warn("[{}] Force cleanup triggered", lingId);
        instancePool.forceCleanupAll(this::destroyInstance);
    }

    private void schedulePeriodicCleanup() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.scheduleAtFixedRate(
                    this::cleanupIdleInstances,
                    config.getDyingCheckIntervalSeconds(),
                    config.getDyingCheckIntervalSeconds(),
                    TimeUnit.SECONDS);
        }
    }

    private void ensurePoolCanAcceptNewInstance() {
        if (!instancePool.canAddInstance()) {
            throw new ServiceUnavailableException(lingId, "System busy: Too many dying instances");
        }
    }

    private void publishStarting(String version) {
        publishExternal(new LingStartingEvent(lingId, version));
    }

    private void startContainerAndReachReady(LingInstance newInstance, LingContext context) {
        String version = newInstance.getVersion();
        try {
            newInstance.getContainer().start(context);
            instanceCoordinator.markReady(newInstance);
        } catch (Exception e) {
            log.error("[{}] Failed to start version {}", lingId, version, e);
            safeDestroy(newInstance);
            throw new LingInstallException(lingId, "Ling start failed", e);
        }
    }

    private void commitStartedInstance(LingInstance newInstance, boolean isDefault) {
        stateLock.lock();
        try {
            ensurePoolCanAcceptNewInstance();
            ensureDefaultCandidateIsReady(newInstance, isDefault);
            LingInstance replacedDefault = instancePool.addInstance(newInstance, isDefault);
            retireReplacedDefault(replacedDefault);
        } catch (RuntimeException e) {
            safeDestroy(newInstance);
            throw e;
        } finally {
            stateLock.unlock();
        }
    }

    private void ensureDefaultCandidateIsReady(LingInstance newInstance, boolean isDefault) {
        if (isDefault && !newInstance.isReady()) {
            log.warn("[{}] New version is NOT READY", lingId);
            throw new LingInstallException(lingId, "New instance failed to become ready");
        }
    }

    private void retireReplacedDefault(LingInstance replacedDefault) {
        if (replacedDefault != null) {
            instancePool.moveToDying(replacedDefault);
        }
    }

    private void publishStarted(String version) {
        publishExternal(new LingStartedEvent(lingId, version));
    }

    private void beginShutdown() {
        stateLock.lock();
        try {
            instancePool.shutdown();
            cleanupIdleInstancesUnderLock();
        } finally {
            stateLock.unlock();
        }
    }

    private int cleanupIdleInstancesUnderLock() {
        int cleaned = instancePool.cleanupIdleInstances(this::destroyInstance);
        if (cleaned > 0) {
            log.debug("[{}] Cleaned up {} idle instances", lingId, cleaned);
        }
        return cleaned;
    }

    private void waitForDyingInstancesToDrain() {
        if (instancePool.getDyingCount() <= 0) {
            return;
        }

        long deadlineMs = System.currentTimeMillis() + config.getForceCleanupDelaySeconds() * 1000L;
        log.info("[{}] Waiting for {} active instances to drain (timeout={}s)",
                lingId, instancePool.getDyingCount(), config.getForceCleanupDelaySeconds());

        while (instancePool.getDyingCount() > 0 && System.currentTimeMillis() < deadlineMs) {
            cleanupIdleInstances();
            if (instancePool.getDyingCount() > 0 && !sleepBeforeNextDrainCheck()) {
                break;
            }
        }

        if (instancePool.getDyingCount() > 0) {
            log.warn("[{}] Force cleanup after timeout, {} instances remaining",
                    lingId, instancePool.getDyingCount());
            forceCleanupAll();
        }
    }

    private boolean sleepBeforeNextDrainCheck() {
        try {
            Thread.sleep(500);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void shutdownManagedResources() {
        if (resourceGuard != null) {
            resourceGuard.shutdown();
        }
    }

    private void destroyInstance(LingInstance instance) {
        if (instance == null || instance.isDestroyed()) {
            return;
        }

        String version = instance.getVersion();
        boolean containerActive = instance.getContainer() != null && instance.getContainer().isActive();
        if (!containerActive) {
            log.debug("[{}] Container already inactive before teardown: {}", lingId, version);
        }

        log.info("[{}] Stopping version: {}", lingId, version);
        publishStopping(version);

        // tearDown 会清掉强引用，因此要先抓取 ClassLoader。
        ClassLoader classLoader = instance.getClassLoader();
        tearDownInstance(instance, version);
        cleanupDetachedResources(version, classLoader);
        publishExternal(new LingStoppedEvent(lingId, version));
        scheduleLeakCheck(version, classLoader);
    }

    private void publishStopping(String version) {
        try {
            publishExternal(new LingStoppingEvent(lingId, version));
        } catch (Exception e) {
            log.error("[{}] Error in Pre-Stop hook", lingId, e);
        }
    }

    private void tearDownInstance(LingInstance instance, String version) {
        try {
            instanceCoordinator.tearDown(instance);
        } catch (Exception e) {
            log.error("[{}] Error destroying instance: {}", lingId, version, e);
        }
    }

    private void cleanupDetachedResources(String version, ClassLoader classLoader) {
        if (classLoader == null) {
            log.warn("[{}] ClassLoader was null before destroy for version {}", lingId, version);
            return;
        }

        try {
            if (resourceGuard != null) {
                resourceGuard.cleanup(lingId, classLoader);
            }
            if (classLoader instanceof AutoCloseable) {
                ((AutoCloseable) classLoader).close();
                log.info("[{}] ClassLoader closed for version {}", lingId, version);
            }
        } catch (Exception e) {
            log.error("[{}] Resource cleanup failed for version {}", lingId, version, e);
        }
    }

    private void scheduleLeakCheck(String version, ClassLoader classLoader) {
        if (classLoader == null || scheduler == null || scheduler.isShutdown()) {
            return;
        }

        WeakReference<ClassLoader> classLoaderRef = new WeakReference<>(classLoader);
        try {
            scheduler.schedule(() -> {
                System.gc();
                if (classLoaderRef.get() != null) {
                    log.warn("[{}] ClassLoader NOT collected after destroy (version={}). Possible leak.",
                            lingId, version);
                } else {
                    log.info("[{}] ClassLoader successfully collected (version={}).",
                            lingId, version);
                }
            }, 5, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // 调度器已经停止时，忽略泄漏检查调度即可。
        }
    }

    private void safeDestroy(LingInstance instance) {
        try {
            instanceCoordinator.tearDown(instance);
        } catch (Exception ignored) {
        }
    }

    private <E extends LingEvent> void publishExternal(E event) {
        if (externalEventBus != null) {
            externalEventBus.publish(event);
        }
    }

    private void checkNotShutdown() {
        if (shutdown.get()) {
            throw new ServiceUnavailableException(lingId, "Lifecycle manager is shutdown");
        }
    }

    public LifecycleStats getStats() {
        return new LifecycleStats(
                shutdown.get(),
                forceCleanupScheduled.get(),
                instancePool.getDyingCount());
    }

    @Value
    public static class LifecycleStats {
        boolean isShutdown;
        boolean forceCleanupScheduled;
        int dyingCount;

        public boolean isShutdown() {
            return isShutdown;
        }

        public boolean forceCleanupScheduled() {
            return forceCleanupScheduled;
        }

        public int dyingCount() {
            return dyingCount;
        }

        @Override
        @NonNull
        public String toString() {
            return String.format("LifecycleStats{shutdown=%s, forceCleanup=%s, dying=%d}",
                    isShutdown, forceCleanupScheduled, dyingCount);
        }
    }
}
