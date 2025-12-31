package com.lingframe.core.plugin;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 插件实例池
 * 职责：管理活跃实例和死亡队列，支持多版本并存
 */
@Slf4j
public class InstancePool {

    private final String pluginId;
    private final int maxDyingInstances;

    // 活跃实例池：支持多版本并存
    private final CopyOnWriteArrayList<PluginInstance> activePool = new CopyOnWriteArrayList<>();

    // 默认实例引用（用于保底路由）
    private final AtomicReference<PluginInstance> defaultInstance = new AtomicReference<>();

    // 死亡队列：存放待销毁的旧版本
    private final ConcurrentLinkedQueue<PluginInstance> dyingQueue = new ConcurrentLinkedQueue<>();

    public InstancePool(String pluginId, int maxDyingInstances) {
        this.pluginId = pluginId;
        this.maxDyingInstances = maxDyingInstances;
    }

    // ==================== 查询方法 ====================

    /**
     * 获取默认实例
     */
    public PluginInstance getDefault() {
        return defaultInstance.get();
    }

    /**
     * 获取所有活跃实例（只读）
     */
    public List<PluginInstance> getActiveInstances() {
        return Collections.unmodifiableList(activePool);
    }

    /**
     * 获取当前版本号
     */
    public String getVersion() {
        PluginInstance instance = defaultInstance.get();
        return instance != null ? instance.getVersion() : null;
    }

    /**
     * 检查是否有可用实例
     */
    public boolean hasAvailableInstance() {
        PluginInstance instance = defaultInstance.get();
        return instance != null && instance.isReady() && !instance.isDying();
    }

    /**
     * 获取死亡队列大小
     */
    public int getDyingCount() {
        return dyingQueue.size();
    }

    /**
     * 检查是否可以添加新实例（背压检查）
     */
    public boolean canAddInstance() {
        return dyingQueue.size() < maxDyingInstances;
    }

    // ==================== 修改方法 ====================

    /**
     * 添加新实例到活跃池
     *
     * @param instance  新实例
     * @param isDefault 是否设为默认
     * @return 被替换的旧默认实例（如果有）
     */
    public PluginInstance addInstance(PluginInstance instance, boolean isDefault) {
        if (instance == null) {
            throw new IllegalArgumentException("Instance cannot be null");
        }

        activePool.add(instance);
        log.debug("[{}] Added instance {} to active pool, pool size: {}",
                pluginId, instance.getVersion(), activePool.size());

        if (isDefault) {
            PluginInstance old = defaultInstance.getAndSet(instance);
            if (old != null && old != instance) {
                log.info("[{}] Replaced default instance: {} -> {}",
                        pluginId, old.getVersion(), instance.getVersion());
                return old;
            }
        }

        return null;
    }

    /**
     * 将实例移入死亡队列
     */
    public void moveToDying(PluginInstance instance) {
        if (instance == null) {
            return;
        }

        instance.markDying();
        activePool.remove(instance);
        dyingQueue.add(instance);

        log.info("[{}] Instance {} moved to dying queue, dying count: {}",
                pluginId, instance.getVersion(), dyingQueue.size());
    }

    /**
     * 清理空闲的死亡实例
     *
     * @param destroyer 销毁回调
     * @return 销毁的实例数量
     */
    public int cleanupIdleInstances(Consumer<PluginInstance> destroyer) {
        int[] count = {0};

        dyingQueue.removeIf(instance -> {
            if (instance.isIdle()) {
                try {
                    if (destroyer != null) {
                        destroyer.accept(instance);
                    } else {
                        instance.destroy();
                    }
                    count[0]++;
                    log.debug("[{}] Cleaned up idle instance: {}", pluginId, instance.getVersion());
                    return true;
                } catch (Exception e) {
                    log.error("[{}] Failed to destroy instance: {}", pluginId, instance.getVersion(), e);
                }
            }
            return false;
        });

        return count[0];
    }

    /**
     * 强制清理所有死亡实例
     *
     * @param destroyer 销毁回调
     */
    public void forceCleanupAll(Consumer<PluginInstance> destroyer) {
        log.warn("[{}] Force cleanup triggered, destroying {} dying instances",
                pluginId, dyingQueue.size());

        dyingQueue.removeIf(instance -> {
            try {
                if (destroyer != null) {
                    destroyer.accept(instance);
                } else {
                    instance.destroy();
                }
            } catch (Exception e) {
                log.error("[{}] Failed to force destroy instance: {}", pluginId, instance.getVersion(), e);
            }
            return true;
        });
    }

    /**
     * 关闭实例池（卸载时调用）
     *
     * @return 需要进入死亡队列的实例列表
     */
    public List<PluginInstance> shutdown() {
        // 清空默认实例
        defaultInstance.set(null);

        // 将所有活跃实例移入死亡队列
        List<PluginInstance> toBeDying = List.copyOf(activePool);
        for (PluginInstance instance : toBeDying) {
            moveToDying(instance);
        }

        log.info("[{}] Instance pool shutdown, {} instances moved to dying queue",
                pluginId, toBeDying.size());

        return toBeDying;
    }

    /**
     * 获取统计信息
     */
    public PoolStats getStats() {
        return new PoolStats(
                activePool.size(),
                dyingQueue.size(),
                defaultInstance.get() != null
        );
    }

    /**
     * 池统计信息
     */
    public record PoolStats(int activeCount, int dyingCount, boolean hasDefault) {
        @Override
        @NonNull
        public String toString() {
            return String.format("PoolStats{active=%d, dying=%d, hasDefault=%s}",
                    activeCount, dyingCount, hasDefault);
        }
    }
}