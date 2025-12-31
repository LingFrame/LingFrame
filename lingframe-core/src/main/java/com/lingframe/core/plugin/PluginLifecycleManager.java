package com.lingframe.core.plugin;

import com.lingframe.api.context.PluginContext;
import com.lingframe.api.event.LingEvent;
import com.lingframe.api.event.lifecycle.*;
import com.lingframe.core.event.EventBus;
import jakarta.annotation.Nonnull;
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
    private final ServiceRegistry serviceRegistry;
    private final EventBus eventBus;
    private final ScheduledExecutorService scheduler;

    private final ReentrantLock stateLock = new ReentrantLock();
    private final AtomicBoolean forceCleanupScheduled = new AtomicBoolean(false);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public PluginLifecycleManager(String pluginId,
                                  InstancePool instancePool,
                                  ServiceRegistry serviceRegistry,
                                  EventBus eventBus,
                                  ScheduledExecutorService scheduler,
                                  PluginRuntimeConfig config) {
        this.pluginId = pluginId;
        this.instancePool = instancePool;
        this.serviceRegistry = serviceRegistry;
        this.eventBus = eventBus;
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
            throw new IllegalStateException("System busy: Too many dying instances");
        }

        String version = newInstance.getVersion();
        log.info("[{}] Starting new version: {}", pluginId, version);

        // Pre-Start 事件
        publishEvent(new PluginStartingEvent(pluginId, version));

        // 清理旧缓存
        serviceRegistry.clear();

        // 启动容器
        try {
            newInstance.getContainer().start(context);
            newInstance.markReady();
        } catch (Exception e) {
            log.error("[{}] Failed to start version {}", pluginId, version, e);
            safeDestroy(newInstance);
            throw new RuntimeException("Plugin start failed", e);
        }

        // 加锁切换状态
        stateLock.lock();
        try {
            // 再次检查背压
            if (!instancePool.canAddInstance()) {
                log.warn("[{}] Backpressure hit after startup", pluginId);
                safeDestroy(newInstance);
                throw new IllegalStateException("System busy: Too many dying instances");
            }

            // 检查就绪状态
            if (isDefault && !newInstance.isReady()) {
                log.warn("[{}] New version is NOT READY", pluginId);
                safeDestroy(newInstance);
                throw new IllegalStateException("New instance failed to become ready");
            }

            // 添加到池并处理旧实例
            PluginInstance old = instancePool.addInstance(newInstance, isDefault);
            if (old != null) {
                instancePool.moveToDying(old);
            }
        } finally {
            stateLock.unlock();
        }

        // Post-Start 事件
        publishEvent(new PluginStartedEvent(pluginId, version));
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
            // 关闭实例池
            instancePool.shutdown();

            // 清理缓存
            serviceRegistry.clear();

            // 立即清理一次
            cleanupIdleInstances();

            // 调度强制清理
            scheduleForceCleanup();

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
                    TimeUnit.SECONDS
            );
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
                        TimeUnit.SECONDS
                );
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
            publishEvent(new PluginStoppingEvent(pluginId, version));
        } catch (Exception e) {
            log.error("[{}] Error in Pre-Stop hook", pluginId, e);
        }

        // 销毁实例
        try {
            instance.destroy();
        } catch (Exception e) {
            log.error("[{}] Error destroying instance: {}", pluginId, version, e);
        }

        // Post-Stop 事件
        publishEvent(new PluginStoppedEvent(pluginId, version));
    }

    private void safeDestroy(PluginInstance instance) {
        try {
            instance.destroy();
        } catch (Exception ignored) {
        }
    }

    private <E extends LingEvent> void publishEvent(E event) {
        if (eventBus != null) {
            eventBus.publish(event);
        }
    }

    private void checkNotShutdown() {
        if (shutdown.get()) {
            throw new IllegalStateException("Lifecycle manager is shutdown");
        }
    }

    // ==================== 统计信息 ====================

    public LifecycleStats getStats() {
        return new LifecycleStats(
                shutdown.get(),
                forceCleanupScheduled.get(),
                instancePool.getDyingCount()
        );
    }

    public record LifecycleStats(
            boolean isShutdown,
            boolean forceCleanupScheduled,
            int dyingCount
    ) {
        @Override
        @Nonnull
        public String toString() {
            return String.format("LifecycleStats{shutdown=%s, forceCleanup=%s, dying=%d}",
                    isShutdown, forceCleanupScheduled, dyingCount);
        }
    }
}