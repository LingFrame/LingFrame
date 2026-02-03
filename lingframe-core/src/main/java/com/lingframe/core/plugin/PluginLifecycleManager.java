package com.lingframe.core.plugin;

import com.lingframe.api.context.PluginContext;
import com.lingframe.api.event.LingEvent;
import com.lingframe.api.event.lifecycle.*;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.exception.PluginInstallException;
import com.lingframe.core.exception.ServiceUnavailableException;
import com.lingframe.core.plugin.event.RuntimeEvent;
import com.lingframe.core.plugin.event.RuntimeEventBus;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 插件生命周期管理器
 * 职责：实例的启动、停止、清理调度
 */
@Slf4j
public class PluginLifecycleManager {

    private final String pluginId;
    private final PluginRuntimeConfig config;
    private final InstancePool instancePool;
    private final RuntimeEventBus internalEventBus; // 内部事件总线
    private final EventBus externalEventBus; // 外部事件总线
    private final ScheduledExecutorService scheduler;

    private final ReentrantLock stateLock = new ReentrantLock();
    private final AtomicBoolean forceCleanupScheduled = new AtomicBoolean(false);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public PluginLifecycleManager(String pluginId,
            InstancePool instancePool,
            RuntimeEventBus internalEventBus,
            EventBus externalEventBus,
            ScheduledExecutorService scheduler,
            PluginRuntimeConfig config) {
        this.pluginId = pluginId;
        this.instancePool = instancePool;
        this.internalEventBus = internalEventBus;
        this.externalEventBus = externalEventBus;
        this.scheduler = scheduler;
        this.config = config;

        // 启动定时清理任务
        schedulePeriodicCleanup();
    }

    // ==================== 实例生命周期 ====================

    /**
     * 添加新实例
     */
    public void addInstance(PluginInstance newInstance, PluginContext context, boolean isDefault) {
        checkNotShutdown();

        // 快速背压检查
        if (!instancePool.canAddInstance()) {
            throw new ServiceUnavailableException(pluginId, "System busy: Too many dying instances");
        }

        String version = newInstance.getVersion();
        log.info("[{}] Starting new version: {}", pluginId, version);

        // 发布外部事件
        publishExternal(new PluginStartingEvent(pluginId, version));

        // 🔥 发布内部事件（通知其他组件准备升级）
        publishInternal(new RuntimeEvent.InstanceUpgrading(pluginId, version));

        // 启动容器
        try {
            newInstance.getContainer().start(context);
            newInstance.markReady();
        } catch (Exception e) {
            log.error("[{}] Failed to start version {}", pluginId, version, e);
            safeDestroy(newInstance);
            throw new PluginInstallException(pluginId, "Plugin start failed", e);
        }

        // 加锁切换状态
        stateLock.lock();
        try {
            // 再次检查背压
            if (!instancePool.canAddInstance()) {
                log.warn("[{}] Backpressure hit after startup", pluginId);
                safeDestroy(newInstance);
                throw new ServiceUnavailableException(pluginId, "System busy: Too many dying instances");
            }

            // 检查就绪状态
            if (isDefault && !newInstance.isReady()) {
                log.warn("[{}] New version is NOT READY", pluginId);
                safeDestroy(newInstance);
                throw new PluginInstallException(pluginId, "New instance failed to become ready");
            }

            // 添加到池并处理旧实例
            PluginInstance old = instancePool.addInstance(newInstance, isDefault);

            // 🔥 发布实例就绪事件
            publishInternal(new RuntimeEvent.InstanceReady(pluginId, version, newInstance));

            if (old != null) {
                instancePool.moveToDying(old);
                // 🔥 发布实例进入死亡状态事件
                publishInternal(new RuntimeEvent.InstanceDying(pluginId, old.getVersion(), old));
            }
        } finally {
            stateLock.unlock();
        }

        publishExternal(new PluginStartedEvent(pluginId, version));
        log.info("[{}] Version {} started", pluginId, version);
    }

    /**
     * 关闭生命周期管理器
     */
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return; // 已经关闭
        }

        stateLock.lock();
        try {
            // 🔥 发布关闭事件（其他组件自己清理）
            publishInternal(new RuntimeEvent.RuntimeShuttingDown(pluginId));

            // 🔥 显式关闭实例池
            instancePool.shutdown();

            // 立即清理一次
            cleanupIdleInstances();

            // 调度强制清理
            scheduleForceCleanup();

            // 🔥 发布已关闭事件
            publishInternal(new RuntimeEvent.RuntimeShutdown(pluginId));

            log.info("[{}] Lifecycle manager shutdown", pluginId);
        } finally {
            stateLock.unlock();
        }
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
                    log.debug("[{}] Cleaned up {} idle instances", pluginId, cleaned);
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
        log.warn("[{}] Force cleanup triggered", pluginId);
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

    private void scheduleForceCleanup() {
        if (scheduler == null || scheduler.isShutdown()) {
            forceCleanupAll();
            return;
        }

        if (forceCleanupScheduled.compareAndSet(false, true)) {
            try {
                scheduler.schedule(
                        this::forceCleanupAll,
                        config.getForceCleanupDelaySeconds(),
                        TimeUnit.SECONDS);
            } catch (RejectedExecutionException e) {
                log.debug("[{}] Scheduler rejected, executing immediately", pluginId);
                forceCleanupAll();
            }
        }
    }

    private void destroyInstance(PluginInstance instance) {
        if (instance == null || instance.isDestroyed()) {
            return;
        }

        String version = instance.getVersion();

        if (!instance.getContainer().isActive()) {
            log.debug("[{}] Container already inactive: {}", pluginId, version);
            return;
        }

        log.info("[{}] Stopping version: {}", pluginId, version);

        // Pre-Stop 事件
        try {
            publishExternal(new PluginStoppingEvent(pluginId, version));
        } catch (Exception e) {
            log.error("[{}] Error in Pre-Stop hook", pluginId, e);
        }

        // 销毁实例
        try {
            instance.destroy();
        } catch (Exception e) {
            log.error("[{}] Error destroying instance: {}", pluginId, version, e);
        }

        // 🔥 发布内部销毁事件
        publishInternal(new RuntimeEvent.InstanceDestroyed(pluginId, version));

        publishExternal(new PluginStoppedEvent(pluginId, version));
    }

    private void safeDestroy(PluginInstance instance) {
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
            throw new ServiceUnavailableException(pluginId, "Lifecycle manager is shutdown");
        }
    }

    // ==================== 统计信息 ====================

    public LifecycleStats getStats() {
        return new LifecycleStats(
                shutdown.get(),
                forceCleanupScheduled.get(),
                instancePool.getDyingCount());
    }

    public record LifecycleStats(
            boolean isShutdown,
            boolean forceCleanupScheduled,
            int dyingCount) {
        @Override
        @NonNull
        public String toString() {
            return String.format("LifecycleStats{shutdown=%s, forceCleanup=%s, dying=%d}",
                    isShutdown, forceCleanupScheduled, dyingCount);
        }
    }
}