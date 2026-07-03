package com.lingframe.core.ling;

import com.lingframe.api.exception.InvalidArgumentException;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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

    // 成员变更互斥锁：保护 activePool/dyingQueue/defaultInstance 的复合操作
    // 使用私有锁对象，避免外部持有 LingInstance 引用导致的锁逃逸和潜在死锁
    private final Object membershipLock = new Object();

    // 关停标记，避免关停期间并发写入
    private volatile boolean isShuttingDown = false;

    // 实例状态的唯一正式写入口。
    // 实例池自己不直接改实例状态，只在成员调整时委托给协调器。
    // ⚠️ 生产就绪约束：构造期强制注入，杜绝"忘记注入导致静默无事件"的僵尸版本风险。
    private final InstanceCoordinator instanceCoordinator;

    public InstancePool(String lingId, int maxDyingInstances, InstanceCoordinator instanceCoordinator) {
        this.lingId = lingId;
        this.maxDyingInstances = maxDyingInstances;
        this.instanceCoordinator = Objects.requireNonNull(instanceCoordinator,
                "InstanceCoordinator is required for InstancePool (prevents silent no-event zombie versions)");
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

        synchronized (membershipLock) {
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

        // 成员变更必须串行化，防止并发 moveToDying/removeInstance 导致 dyingQueue 不一致
        synchronized (membershipLock) {
            activePool.remove(instance);
            dyingQueue.add(instance);

            // 若该实例曾是主实例，原子选举新的主实例（避免中间 null 窗口）
            if (defaultInstance.get() == instance) {
                LingInstance newDefault = electDefaultCandidate();
                if (defaultInstance.compareAndSet(instance, newDefault)) {
                    if (newDefault != null) {
                        log.info("[{}] Default instance moved to dying, promoted {} to new default", lingId,
                                newDefault.getVersion());
                    }
                }
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

        // 与 moveToDying 共用 membershipLock，确保成员操作串行化
        synchronized (membershipLock) {
            activePool.remove(instance);
            dyingQueue.remove(instance);

            // 若为默认实例，原子选举新主（避免中间 null 窗口）
            if (defaultInstance.get() == instance) {
                LingInstance newDefault = electDefaultCandidate();
                if (defaultInstance.compareAndSet(instance, newDefault)) {
                    if (newDefault != null) {
                        log.info("[{}] Default instance removed, promoted {} to new default", lingId, newDefault.getVersion());
                    }
                }
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
     * 从活跃池中按确定性优先级选举新主候选。
     * <p>
     * 此前的实现是 {@code activePool.get(0)}，依赖 CopyOnWriteArrayList 的插入顺序，
     * 选举结果不确定：并发 addInstance 时由调用时序决定，且可能把灰度实例选为默认，
     * 等同于灰度全量上线，违反灰度语义。
     * <p>
     * 本方法只基于实例自身可用信息排序，不依赖 CanaryRouter（避免 core 内循环依赖）：
     * <ol>
     *   <li>READY 实例优先于非 READY 实例（保底路由到可用实例）；</li>
     *   <li>同为 READY 时，版本号语义降序优先（新版本通常承载最新修复与能力）；</li>
     *   <li>版本号相同则保持稳定顺序（按 activePool 自然顺序），避免无谓抖动。</li>
     * </ol>
     * 注意：版本号必须按语义版本序（1.10.0 &gt; 1.9.0），而非字典序。
     * 字典序会把 1.9.0 排在 1.10.0 之前导致旧版本压制新版本，违反选举意图。
     * <p>
     * 调用方必须已持有 {@link #membershipLock}，因此本方法不加锁。
     *
     * @return 选举出的新主候选；活跃池为空时返回 null
     */
    private LingInstance electDefaultCandidate() {
        if (activePool.isEmpty()) {
            return null;
        }
        return activePool.stream()
                .min(Comparator
                        .comparing((LingInstance inst) -> !inst.isReady())   // READY 在前（!isReady=false=0 排序在前）
                        .thenComparing(LingInstance::getVersion,
                                InstancePool::compareVersionDescending))      // 语义版本降序
                .orElse(null);
    }

    /**
     * 语义版本号降序比较：返回值约定同 {@link Comparator#compare(Object, Object)}，
     * 即 v1 &gt; v2 时返回负数（v1 排前），实现「新版本优先」。
     * <p>
     * 按 {@code .} 分段，每段尝试解析为数字：
     * <ul>
     *   <li>纯数字段按数值比较（1.10 &gt; 1.9，避免字典序陷阱）；</li>
     *   <li>非数字段（如 alpha/beta/RC1）回退字典序，保证有确定顺序；</li>
     *   <li>段数不一致时，缺失段视为 0（1.2 等价于 1.2.0）。</li>
     * </ul>
     * 非法或 null 输入兜底为相等，避免拖垮选举。
     */
    private static int compareVersionDescending(String v1, String v2) {
        if (v1 == null || v2 == null) {
            return 0;
        }
        String[] p1 = v1.split("\\.");
        String[] p2 = v2.split("\\.");
        int len = Math.max(p1.length, p2.length);
        for (int i = 0; i < len; i++) {
            String s1 = i < p1.length ? p1[i] : "";
            String s2 = i < p2.length ? p2[i] : "";
            int n1 = tryParseInt(s1);
            int n2 = tryParseInt(s2);
            int cmp;
            if (n1 != Integer.MIN_VALUE && n2 != Integer.MIN_VALUE) {
                // 两段都是数字：按数值比较
                cmp = Integer.compare(n1, n2);
            } else {
                // 至少一段非数字：回退字典序（空串排前）
                cmp = s1.compareTo(s2);
            }
            if (cmp != 0) {
                return -cmp;   // 取负实现降序
            }
        }
        return 0;
    }

    private static int tryParseInt(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;   // 哨兵值表示非数字
        }
    }

    /**
     * 池统计信息。
     * <p>
     * 统一使用 record-style 访问器（{@code activeCount()} 而非 {@code getActiveCount()}），
     * 不混用 Lombok @Value getter，消除重复访问器导致的风格歧义。
     */
    public static class PoolStats {
        private final int activeCount;
        private final int dyingCount;
        private final boolean hasDefault;

        public PoolStats(int activeCount, int dyingCount, boolean hasDefault) {
            this.activeCount = activeCount;
            this.dyingCount = dyingCount;
            this.hasDefault = hasDefault;
        }

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
}
