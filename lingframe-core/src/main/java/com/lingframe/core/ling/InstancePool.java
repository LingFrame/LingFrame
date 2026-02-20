package com.lingframe.core.ling;

import com.lingframe.core.ling.event.RuntimeEvent;
import com.lingframe.core.ling.event.RuntimeEventBus;
import com.lingframe.api.exception.InvalidArgumentException;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 单元实例池
 * 职责：管理活跃实例和死亡队列，支持多版本并存
 */
@Slf4j
public class InstancePool {

    private final String lingId;
    private final int maxDyingInstances;

    // 活跃实例池：支持多版本并存
    private final CopyOnWriteArrayList<LingInstance> activePool = new CopyOnWriteArrayList<>();

    // 默认实例引用（用于保底路由）
    private final AtomicReference<LingInstance> defaultInstance = new AtomicReference<>();

    // 死亡队列：存放待销毁的旧版本
    private final ConcurrentLinkedQueue<LingInstance> dyingQueue = new ConcurrentLinkedQueue<>();

    public InstancePool(String lingId, int maxDyingInstances) {
        this.lingId = lingId;
        this.maxDyingInstances = maxDyingInstances;
    }

    /**
     * 注册事件监听
     */
    public void registerEventHandlers(RuntimeEventBus eventBus) {
        eventBus.subscribe(RuntimeEvent.RuntimeShuttingDown.class, this::onRuntimeShuttingDown);
        log.debug("[{}] InstancePool event handlers registered", lingId);
    }

    private void onRuntimeShuttingDown(RuntimeEvent.RuntimeShuttingDown event) {
        log.debug("[{}] Runtime shutting down, initiating pool shutdown", lingId);
        shutdown();
    }

    // ==================== 查询方法 ====================

    /**
     * 获取默认实例
     */
    public LingInstance getDefault() {
        return defaultInstance.get();
    }

    /**
     * 获取所有活跃实例（只读）
     */
    public List<LingInstance> getActiveInstances() {
        return Collections.unmodifiableList(activePool);
    }

    /**
     * 获取当前版本号
     */
    public String getVersion() {
        LingInstance instance = defaultInstance.get();
        return instance != null ? instance.getVersion() : null;
    }

    /**
     * 检查是否有可用实例
     */
    public boolean hasAvailableInstance() {
        return activePool.stream().anyMatch(instance -> instance.isReady() && !instance.isDying());
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
    public LingInstance addInstance(LingInstance instance, boolean isDefault) {
        if (instance == null) {
            throw new InvalidArgumentException("instance", "Instance cannot be null");
        }

        activePool.add(instance);
        log.debug("[{}] Added instance {} to active pool, pool size: {}",
                lingId, instance.getVersion(), activePool.size());

        if (isDefault) {
            LingInstance old = defaultInstance.getAndSet(instance);
            if (old != null && old != instance) {
                log.info("[{}] Replaced default instance: {} -> {}",
                        lingId, old.getVersion(), instance.getVersion());
                return old;
            }
        }

        return null;
    }

    /**
     * 将实例移入死亡队列
     */
    public void moveToDying(LingInstance instance) {
        if (instance == null) {
            return;
        }

        instance.markDying();
        activePool.remove(instance);
        dyingQueue.add(instance);

        log.info("[{}] Instance {} moved to dying queue, dying count: {}",
                lingId, instance.getVersion(), dyingQueue.size());
    }

    /**
     * 清理空闲的死亡实例
     *
     * @param destroyer 销毁回调
     * @return 销毁的实例数量
     */
    public int cleanupIdleInstances(Consumer<LingInstance> destroyer) {
        int[] count = { 0 };

        dyingQueue.removeIf(instance -> {
            if (instance.isIdle()) {
                try {
                    if (destroyer != null) {
                        destroyer.accept(instance);
                    } else {
                        instance.destroy();
                    }
                    count[0]++;
                    log.debug("[{}] Cleaned up idle instance: {}", lingId, instance.getVersion());
                    return true;
                } catch (Exception e) {
                    log.error("[{}] Failed to destroy instance: {}", lingId, instance.getVersion(), e);
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
    public void forceCleanupAll(Consumer<LingInstance> destroyer) {
        log.warn("[{}] Force cleanup triggered, destroying {} dying instances",
                lingId, dyingQueue.size());

        dyingQueue.removeIf(instance -> {
            try {
                if (destroyer != null) {
                    destroyer.accept(instance);
                } else {
                    instance.destroy();
                }
            } catch (Exception e) {
                log.error("[{}] Failed to force destroy instance: {}", lingId, instance.getVersion(), e);
            }
            return true;
        });
    }

    /**
     * 关闭实例池（卸载时调用）
     *
     * @return 需要进入死亡队列的实例列表
     */
    public List<LingInstance> shutdown() {
        // 清空默认实例
        defaultInstance.set(null);

        // 将所有活跃实例移入死亡队列
        List<LingInstance> toBeDying = new ArrayList<>(activePool);
        for (LingInstance instance : toBeDying) {
            moveToDying(instance);
        }

        log.info("[{}] Instance pool shutdown, {} instances moved to dying queue",
                lingId, toBeDying.size());

        return toBeDying;
    }

    /**
     * 获取统计信息
     */
    public PoolStats getStats() {
        return new PoolStats(
                activePool.size(),
                dyingQueue.size(),
                defaultInstance.get() != null);
    }

    /**
     * 池统计信息
     */
    @Value
    public static class PoolStats {
        int activeCount;
        int dyingCount;
        boolean hasDefault;

        public int activeCount(){return activeCount;}
        public int dyingCount(){return dyingCount;}
        public boolean hasDefault(){return hasDefault;}

        @Override
        @NonNull
        public String toString() {
            return String.format("PoolStats{active=%d, dying=%d, hasDefault=%s}",
                    activeCount, dyingCount, hasDefault);
        }
    }
}