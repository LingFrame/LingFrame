package com.lingframe.core.ling;

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
 * 灵元实例成员池。
 * <p>
 * 它只负责成员关系与版本池管理：
 * 活跃实例列表、默认实例指针、死亡队列和背压判断。
 * <p>
 * 它不是生命周期编排器，也不是状态机真源。
 * 当实例需要进入 STOPPING / DEAD 时，仍必须委托给
 * {@link InstanceCoordinator}，从而保证实例事件与
 * {@link com.lingframe.core.fsm.RuntimeCoordinator} 的聚合快照保持一致。
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

    // 关停标记，避免关停期间并发写入
    private volatile boolean isShuttingDown = false;

    // 实例状态的唯一正式写入口。
    // 实例池自己不直接改实例状态，只在成员调整时委托给协调器。
    private volatile InstanceCoordinator instanceCoordinator = new InstanceCoordinator(null);

    public InstancePool(String lingId, int maxDyingInstances) {
        this.lingId = lingId;
        this.maxDyingInstances = maxDyingInstances;
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
     * 根据版本获取指定活跃实例
     */
    public LingInstance getInstance(String version) {
        if (version == null) {
            return null;
        }
        for (LingInstance instance : activePool) {
            if (version.equals(instance.getVersion())) {
                return instance;
            }
        }
        return null;
    }

    /**
     * 获取所有实例（含活跃与死亡队列）
     */
    public List<LingInstance> getAllInstances() {
        List<LingInstance> all = new ArrayList<>(activePool);
        all.addAll(dyingQueue);
        return all;
    }

    /**
     * 获取当前默认版本号
     */
    public String getVersion() {
        LingInstance instance = defaultInstance.get();
        return instance != null ? instance.getVersion() : null;
    }

    /**
     * 是否有可用实例
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
     * 是否允许添加新实例（背压检测）
     */
    public boolean canAddInstance() {
        return dyingQueue.size() < maxDyingInstances;
    }

    // ==================== 修改方法 ====================

    /**
     * 添加新实例到活跃池。
     * <p>
     * 这里只提交“池成员关系”，不负责把实例推进到 READY。
     * READY 事实必须由 {@link InstanceCoordinator} 统一发布。
     *
     * @param instance  新实例
     * @param isDefault 是否设置为默认
     * @return 被替换的旧默认实例（如果有）
     */
    public LingInstance addInstance(LingInstance instance, boolean isDefault) {
        if (instance == null) {
            throw new InvalidArgumentException("instance", "Instance cannot be null");
        }

        if (isShuttingDown) {
            log.warn("[{}] Cannot add instance {} because pool is shutting down", lingId, instance.getVersion());
            return null;
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
     * 将实例移入死亡队列。
     * <p>
     * 这是“成员关系变化 + 状态联动”的灵核动作：
     * 先通过协调器确保实例进入 STOPPING，
     * 再把它从 activePool 迁到 dyingQueue。
     */
    public void moveToDying(LingInstance instance) {
        if (instance == null) {
            return;
        }

        if (!instance.isDying()) {
            instanceCoordinator.stop(instance);
            log.debug("[{}] Instance {} marked STOPPING via InstanceCoordinator",
                    lingId, instance.getVersion());
        }
        activePool.remove(instance);
        dyingQueue.add(instance);

        // 若该实例曾是主实例，从活跃池选举新的主实例
        if (defaultInstance.compareAndSet(instance, null)) {
            if (!activePool.isEmpty()) {
                LingInstance newDefault = activePool.get(0);
                defaultInstance.set(newDefault);
                log.info("[{}] Default instance moved to dying, promoted {} to new default", lingId,
                        newDefault.getVersion());
            }
        }

        log.info("[{}] Instance {} moved to dying queue, dying count: {}",
                lingId, instance.getVersion(), dyingQueue.size());
    }

    /**
     * 彻底从池中移除实例（活跃池 + 死亡队列）。
     * <p>
     * 这里只移除成员关系，不隐式触发 teardown。
     * 调用方应先完成销毁，再调用本方法回收池内引用。
     */
    public void removeInstance(LingInstance instance) {
        if (instance == null) {
            return;
        }
        activePool.remove(instance);
        dyingQueue.remove(instance);

        // 若为默认实例，清除默认标记并选举新主
        if (defaultInstance.compareAndSet(instance, null)) {
            if (!activePool.isEmpty()) {
                LingInstance newDefault = activePool.get(0);
                defaultInstance.set(newDefault);
                log.info("[{}] Default instance removed, promoted {} to new default", lingId, newDefault.getVersion());
            }
        }
    }

    /**
     * 清理空闲的死亡实例。
     * <p>
     * 如果未提供 destroyer，则默认仍经由 {@link InstanceCoordinator#tearDown(LingInstance)}
     * 完成最终销毁，保持状态事件链完整。
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
                        instanceCoordinator.tearDown(instance);
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
     * 强制清理所有死亡实例。
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
                    instanceCoordinator.tearDown(instance);
                }
            } catch (Exception e) {
                log.error("[{}] Failed to force destroy instance: {}", lingId, instance.getVersion(), e);
            }
            return true;
        });
    }

    /**
     * 关闭实例池（卸载时调用）。
     * <p>
     * 它只做两件事：
     * 1. 阻止新实例再加入
     * 2. 把当前活跃实例整体转入 dyingQueue
     * <p>
     * RuntimeStatus 进入 STOPPING / REMOVED 仍由 RuntimeCoordinator 决定。
     *
     * @return 进入死亡队列的实例列表
     */
    public List<LingInstance> shutdown() {
        // 标记关停，避免新实例并发加入
        isShuttingDown = true;

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

        public int activeCount() {
            return activeCount;
        }

        public int dyingCount() {
            return dyingCount;
        }

        public boolean hasDefault() {
            return hasDefault;
        }

        @Override
        @NonNull
        public String toString() {
            return String.format("PoolStats{active=%d, dying=%d, hasDefault=%s}",
                    activeCount, dyingCount, hasDefault);
        }
    }

    /**
     * 绑定实例状态协同器（可选），用于统一状态转换与事件联动。
     * <p>
     * 如果未注入，则退化为一个不发事件的本地协调器，便于测试或离线使用。
     */
    void setInstanceCoordinator(InstanceCoordinator instanceCoordinator) {
        this.instanceCoordinator = instanceCoordinator != null ? instanceCoordinator : new InstanceCoordinator(null);
    }
}
