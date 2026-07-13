package com.lingframe.core.ling;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.RuntimeStateChangedEvent;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import lombok.Getter;
import lombok.ToString;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 灵元运行时聚合体。
 * <p>
 * 它在当前架构中是一个“只读灵核聚合对象”：
 * 负责持有运行时配置、流量统计和多版本实例池，
 * 但不再拥有也不再直接修改 {@link RuntimeStatus} 状态机。
 * <p>
 * 运行时宏观状态的唯一真源统一收敛到 {@link RuntimeCoordinator}，
 * {@code LingRuntime} 只能通过 {@link #currentStatus()} 读取。
 * 生命周期编排、实例切换、运行时联动分别由
 * {@link DefaultLingLifecycleEngine}、{@link InstancePool}、
 * {@link RuntimeCoordinator} 完成。
 */
@ToString
public class LingRuntime {

    @Getter
    private final String lingId;

    @Getter
    private volatile LingRuntimeConfig config;

    @Getter
    private final InstancePool instancePool;

    // LingRuntime 不再持有运行时状态机。
    // 这里保留的是一个只读访问点，用于查询宏观运行时状态。
    private final RuntimeCoordinator runtimeCoordinator;

    // 流量统计
    @Getter
    private final AtomicLong totalRequests = new AtomicLong(0);
    @Getter
    private final AtomicLong stableRequests = new AtomicLong(0);
    @Getter
    private final AtomicLong canaryRequests = new AtomicLong(0);
    @Getter
    private final AtomicLong activeRequests = new AtomicLong(0);
    @Getter
    private volatile long statsWindowStart = System.currentTimeMillis();

    @Getter
    private final long installedAt = System.currentTimeMillis();

    public LingRuntime(String lingId, LingRuntimeConfig config, EventBus eventBus,
                InstanceCoordinator instanceCoordinator, RuntimeCoordinator runtimeCoordinator) {
        this.lingId = lingId;
        this.config = config != null ? config : LingRuntimeConfig.defaults();
        this.instancePool = new InstancePool(lingId, this.config.getMaxHistorySnapshots(), instanceCoordinator);
        this.runtimeCoordinator = Objects.requireNonNull(runtimeCoordinator, "RuntimeCoordinator is required");

        // ⚠️ 职责边界：运行时聚合器注册由编排层（DefaultLingLifecycleEngine.ensureRuntimeForDeployment）单次调用，
        // LingRuntime 自身不注册，消除原双重注册的时序耦合。
        if (eventBus != null) {
            eventBus.subscribe(lingId, RuntimeStateChangedEvent.class, this::handleStateChanged);
        }
    }

    private void handleStateChanged(RuntimeStateChangedEvent event) {
        if (event == null || event.getLingId() == null || !event.getLingId().equals(lingId)) {
            return;
        }
        RuntimeStatus newStatus = event.getTo();

        // 宏观运行时进入 STOPPING/REMOVED 后，灵核只需要同步收紧成员池写入。
        // 这里不反向写 RuntimeStatus，避免对象之间互相改写状态。
        if (newStatus == RuntimeStatus.STOPPING || newStatus == RuntimeStatus.REMOVED) {
            instancePool.shutdown();
        }
    }

    public void recordRequest(boolean isCanary) {
        totalRequests.incrementAndGet();
        if (isCanary) {
            canaryRequests.incrementAndGet();
        } else {
            stableRequests.incrementAndGet();
        }
    }

    public void resetTrafficStats() {
        totalRequests.set(0);
        stableRequests.set(0);
        canaryRequests.set(0);
        activeRequests.set(0);
        statsWindowStart = System.currentTimeMillis();
    }

    public void startRequest() {
        activeRequests.incrementAndGet();
    }

    public void endRequest() {
        activeRequests.decrementAndGet();
    }

    public boolean isAvailable() {
        return currentStatus() == RuntimeStatus.ACTIVE &&
                instancePool.hasAvailableInstance();
    }

    /**
     * 替换运行时配置。
     * <p>
     * 由治理配置变更链路调用，将 GovernancePolicy 中的调用治理参数
     * 合并到 LingRuntimeConfig，使 Pipeline Filter 下次调用自然读到新值。
     * <p>
     * ⚠️ 此方法是引用替换（volatile 写），不是字段修改，线程安全。
     */
    public void updateConfig(LingRuntimeConfig newConfig) {
        this.config = newConfig != null ? newConfig : LingRuntimeConfig.defaults();
    }

    /**
     * 宏观状态便捷方法。
     * 外部读取运行时状态只能走这里，真源完全归 RuntimeCoordinator 所有。
     */
    public RuntimeStatus currentStatus() {
        return runtimeCoordinator.getStatus(lingId);
    }

    /**
     * 获取所有 READY 状态实例（用于路由选择）
     */
    public List<LingInstance> getReadyInstances() {
        return instancePool.getActiveInstances().stream()
                .filter(LingInstance::isReady)
                .collect(Collectors.toList());
    }
}
