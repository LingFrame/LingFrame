package com.lingframe.core.plugin;

import com.lingframe.api.context.PluginContext;
import com.lingframe.api.event.LingEvent;
import com.lingframe.api.event.lifecycle.PluginStartedEvent;
import com.lingframe.api.event.lifecycle.PluginStartingEvent;
import com.lingframe.api.event.lifecycle.PluginStoppedEvent;
import com.lingframe.api.event.lifecycle.PluginStoppingEvent;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.exception.PluginInstallException;
import com.lingframe.core.exception.ServiceUnavailableException;
import com.lingframe.core.plugin.event.RuntimeEvent;
import com.lingframe.core.plugin.event.RuntimeEventBus;
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
    private final ResourceGuard resourceGuard;

    private final ReentrantLock stateLock = new ReentrantLock();
    private final AtomicBoolean forceCleanupScheduled = new AtomicBoolean(false);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public PluginLifecycleManager(String pluginId,
                                  InstancePool instancePool,
                                  RuntimeEventBus internalEventBus,
                                  EventBus externalEventBus,
                                  ScheduledExecutorService scheduler,
                                  PluginRuntimeConfig config,
                                  ResourceGuard resourceGuard) {
        this.pluginId = pluginId;
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
            publishInternal(new RuntimeEvent.RuntimeShuttingDown(pluginId));

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
                    pluginId, instancePool.getDyingCount(),
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
                        pluginId, instancePool.getDyingCount());
                forceCleanupAll();
            }
        }

        // 🔥 发布已关闭事件
        publishInternal(new RuntimeEvent.RuntimeShutdown(pluginId));

        log.info("[{}] Lifecycle manager shutdown complete", pluginId);
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

    // scheduleForceCleanup() 已移除：shutdown() 改为同步等待 + 超时强制清理

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
        // 🔥 关键：在 destroy 之前保存 ClassLoader 引用，因为 destroy 后容器会清空它
        ClassLoader cl = instance.getContainer().getClassLoader();

        try {
            instance.destroy();
        } catch (Exception e) {
            log.error("[{}] Error destroying instance: {}", pluginId, version, e);
        }

        // 🔥 资源清理 (在实例销毁后执行)
        if (cl != null) {
            try {
                resourceGuard.cleanup(pluginId, cl);
                resourceGuard.detectLeak(pluginId, cl);

                // 🔥 关键：关闭 ClassLoader 释放 JAR 文件句柄
                if (cl instanceof AutoCloseable) {
                    ((AutoCloseable) cl).close();
                    log.info("[{}] ClassLoader closed for version {}", pluginId, version);
                }
            } catch (Exception e) {
                log.error("[{}] Resource cleanup failed for version {}", pluginId, version, e);
            }
        } else {
            log.warn("[{}] ClassLoader was null before destroy for version {}", pluginId, version);
        }

        // 🔥 发布内部销毁事件
        publishInternal(new RuntimeEvent.InstanceDestroyed(pluginId, version));

        publishExternal(new PluginStoppedEvent(pluginId, version));

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
                                pluginId, ver);
                    } else {
                        log.info("[{}] ✅ ClassLoader successfully collected (version={}).",
                                pluginId, ver);
                    }
                }, 5, TimeUnit.SECONDS);
            } catch (RejectedExecutionException ignored) {
                // scheduler 已关闭，跳过检测
            }
        }
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