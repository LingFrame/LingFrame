package com.lingframe.core.ling;

import com.lingframe.api.context.LingContext;
import com.lingframe.api.event.LingEvent;
import com.lingframe.api.event.lifecycle.LingStartedEvent;
import com.lingframe.api.event.lifecycle.LingStartingEvent;
import com.lingframe.api.event.lifecycle.LingStoppedEvent;
import com.lingframe.api.event.lifecycle.LingStoppingEvent;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.exception.LingInstallException;
import com.lingframe.core.exception.ServiceUnavailableException;
import com.lingframe.core.ling.event.RuntimeEvent;
import com.lingframe.core.ling.event.RuntimeEventBus;
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
 * 灵元生命周期管理器
 * 职责：实例的启动、停止、清理调度
 */
@Slf4j
public class LingLifecycleManager {

    private final String lingId;
    private final LingRuntimeConfig config;
    private final InstancePool instancePool;
    private final RuntimeEventBus internalEventBus; // 内部事件总线
    private final EventBus externalEventBus; // 外部事件总线
    private final ScheduledExecutorService scheduler;
    private final ResourceGuard resourceGuard;

    private final ReentrantLock stateLock = new ReentrantLock();
    private final AtomicBoolean forceCleanupScheduled = new AtomicBoolean(false);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public LingLifecycleManager(String lingId,
            InstancePool instancePool,
            RuntimeEventBus internalEventBus,
            EventBus externalEventBus,
            ScheduledExecutorService scheduler,
            LingRuntimeConfig config,
            ResourceGuard resourceGuard) {
        this.lingId = lingId;
        this.instancePool = instancePool;
        this.internalEventBus = internalEventBus;
        this.externalEventBus = externalEventBus;
        this.scheduler = scheduler;
        this.config = config;
        this.resourceGuard = resourceGuard;

        // 启动定时清理任务
        schedulePeriodicCleanup();
    }

    // ==================== 实例生命周期 ====================

    /**
     * 添加新实例
     */
    public void addInstance(LingInstance newInstance, LingContext context, boolean isDefault) {
        checkNotShutdown();

        // 快速背压检查
        if (!instancePool.canAddInstance()) {
            throw new ServiceUnavailableException(lingId, "System busy: Too many dying instances");
        }

        String version = newInstance.getVersion();
        log.info("[{}] Starting new version: {}", lingId, version);

        // 发布外部事件
        publishExternal(new LingStartingEvent(lingId, version));

        // 🔥 发布内部事件（通知其他组件准备升级）
        publishInternal(new RuntimeEvent.InstanceUpgrading(lingId, version));

        // 启动容器
        try {
            newInstance.getContainer().start(context);
            newInstance.markReady();
        } catch (Exception e) {
            log.error("[{}] Failed to start version {}", lingId, version, e);
            safeDestroy(newInstance);
            throw new LingInstallException(lingId, "Ling start failed", e);
        }

        // 加锁切换状态
        stateLock.lock();
        try {
            // 再次检查背压
            if (!instancePool.canAddInstance()) {
                log.warn("[{}] Backpressure hit after startup", lingId);
                safeDestroy(newInstance);
                throw new ServiceUnavailableException(lingId, "System busy: Too many dying instances");
            }

            // 检查就绪状态
            if (isDefault && !newInstance.isReady()) {
                log.warn("[{}] New version is NOT READY", lingId);
                safeDestroy(newInstance);
                throw new LingInstallException(lingId, "New instance failed to become ready");
            }

            // 添加到池并处理旧实例
            LingInstance old = instancePool.addInstance(newInstance, isDefault);

            // 🔥 发布实例就绪事件
            publishInternal(new RuntimeEvent.InstanceReady(lingId, version, newInstance));

            if (old != null) {
                instancePool.moveToDying(old);
                // 🔥 发布实例进入死亡状态事件
                publishInternal(new RuntimeEvent.InstanceDying(lingId, old.getVersion(), old));
            }
        } finally {
            stateLock.unlock();
        }

        publishExternal(new LingStartedEvent(lingId, version));
        log.info("[{}] Version {} started", lingId, version);
    }

    /**
     * 关闭生命周期管理器
     * <p>
     * 同步等待活跃请求完成（带超时），超时后强制清理。
     * 不再依赖异步 scheduleForceCleanup() 闭包作为唯一回收路径。
     * </p>
     */
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return; // 已经关闭
        }

        stateLock.lock();
        try {
            // 🔥 发布关闭事件（其他组件自己清理）
            publishInternal(new RuntimeEvent.RuntimeShuttingDown(lingId));

            // 🔥 显式关闭实例池
            instancePool.shutdown();

            // 立即清理一次
            cleanupIdleInstances();
        } finally {
            stateLock.unlock();
        }

        // 🔥 同步等待活跃请求完成（不持有 stateLock，避免死锁）
        if (instancePool.getDyingCount() > 0) {
            long deadlineMs = System.currentTimeMillis()
                    + config.getForceCleanupDelaySeconds() * 1000L;
            log.info("[{}] Waiting for {} active instances to drain (timeout={}s)",
                    lingId, instancePool.getDyingCount(),
                    config.getForceCleanupDelaySeconds());

            while (instancePool.getDyingCount() > 0
                    && System.currentTimeMillis() < deadlineMs) {
                cleanupIdleInstances();
                if (instancePool.getDyingCount() > 0) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            // 超时后强制清理
            if (instancePool.getDyingCount() > 0) {
                log.warn("[{}] Force cleanup after timeout, {} instances remaining",
                        lingId, instancePool.getDyingCount());
                forceCleanupAll();
            }
        }

        // 🔥 发布已关闭事件
        publishInternal(new RuntimeEvent.RuntimeShutdown(lingId));

        log.info("[{}] Lifecycle manager shutdown complete", lingId);
    }

    /**
     * 检查是否已关闭
     */
    public boolean isShutdown() {
        return shutdown.get();
    }

    // ==================== 清理任务 ====================

    /**
     * 清理空闲的死亡实例
     */
    public int cleanupIdleInstances() {
        if (stateLock.tryLock()) {
            try {
                int cleaned = instancePool.cleanupIdleInstances(this::destroyInstance);
                if (cleaned > 0) {
                    log.debug("[{}] Cleaned up {} idle instances", lingId, cleaned);
                }
                return cleaned;
            } finally {
                stateLock.unlock();
            }
        }
        return 0;
    }

    /**
     * 强制清理所有死亡实例
     */
    public void forceCleanupAll() {
        log.warn("[{}] Force cleanup triggered", lingId);
        instancePool.forceCleanupAll(this::destroyInstance);
    }

    // ==================== 内部方法 ====================

    private void schedulePeriodicCleanup() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.scheduleAtFixedRate(
                    this::cleanupIdleInstances,
                    config.getDyingCheckIntervalSeconds(),
                    config.getDyingCheckIntervalSeconds(),
                    TimeUnit.SECONDS);
        }
    }

    // scheduleForceCleanup() 已移除：shutdown() 改为同步等待 + 超时强制清理

    private void destroyInstance(LingInstance instance) {
        if (instance == null || instance.isDestroyed()) {
            return;
        }

        String version = instance.getVersion();

        if (!instance.getContainer().isActive()) {
            log.debug("[{}] Container already inactive: {}", lingId, version);
            return;
        }

        log.info("[{}] Stopping version: {}", lingId, version);

        // Pre-Stop 事件
        try {
            publishExternal(new LingStoppingEvent(lingId, version));
        } catch (Exception e) {
            log.error("[{}] Error in Pre-Stop hook", lingId, e);
        }

        // 销毁实例
        // 🔥 关键：在 destroy 之前保存 ClassLoader 引用，因为 destroy 后容器会清空它
        ClassLoader cl = instance.getContainer().getClassLoader();

        try {
            instance.destroy();
        } catch (Exception e) {
            log.error("[{}] Error destroying instance: {}", lingId, version, e);
        }

        // 🔥 资源清理 (在实例销毁后执行)
        if (cl != null) {
            try {
                resourceGuard.cleanup(lingId, cl);
                resourceGuard.detectLeak(lingId, cl);

                // 🔥 关键：关闭 ClassLoader 释放 JAR 文件句柄
                if (cl instanceof AutoCloseable) {
                    ((AutoCloseable) cl).close();
                    log.info("[{}] ClassLoader closed for version {}", lingId, version);
                }
            } catch (Exception e) {
                log.error("[{}] Resource cleanup failed for version {}", lingId, version, e);
            }
        } else {
            log.warn("[{}] ClassLoader was null before destroy for version {}", lingId, version);
        }

        // 🔥 发布内部销毁事件
        publishInternal(new RuntimeEvent.InstanceDestroyed(lingId, version));

        publishExternal(new LingStoppedEvent(lingId, version));

        // 🔥 ClassLoader GC 检测增强：延迟检查确认回收状态
        WeakReference<ClassLoader> clRef = new WeakReference<>(cl);
        // 主动断开本地引用
        final String ver = version;
        if (scheduler != null && !scheduler.isShutdown()) {
            try {
                scheduler.schedule(() -> {
                    System.gc();
                    if (clRef.get() != null) {
                        log.warn("[{}] ⚠️ ClassLoader NOT collected after destroy (version={}). Possible leak.",
                                lingId, ver);
                    } else {
                        log.info("[{}] ✅ ClassLoader successfully collected (version={}).",
                                lingId, ver);
                    }
                }, 5, TimeUnit.SECONDS);
            } catch (RejectedExecutionException ignored) {
                // scheduler 已关闭，跳过检测
            }
        }
    }

    private void safeDestroy(LingInstance instance) {
        try {
            instance.destroy();
        } catch (Exception ignored) {
        }
    }

    private void publishInternal(RuntimeEvent event) {
        if (internalEventBus != null) {
            internalEventBus.publish(event);
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

    // ==================== 统计信息 ====================

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