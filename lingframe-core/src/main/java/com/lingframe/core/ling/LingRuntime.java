package com.lingframe.core.ling;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.RuntimeStateChangedEvent;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.spi.RoutableTarget;
import lombok.Getter;
import lombok.ToString;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
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
 * <p>
 * 路由升维：实现 {@link RoutableTarget} 窄接口，使 Pipeline 不再直接依赖本具体类，
 * 灵核和灵元都能通过 {@code RoutableTarget} 类型统一表达。
 */
@ToString
public class LingRuntime implements RoutableTarget {

    @Getter
    private final String lingId;

    @Getter
    private volatile LingRuntimeConfig config;

    @Getter
    private final InstancePool instancePool;

    // LingRuntime 不再持有运行时状态机。
    // 这里保留的是一个只读访问点，用于查询宏观运行时状态。
    private final RuntimeCoordinator runtimeCoordinator;

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

    /**
     * 面向纯治理虚拟灵元的包级私有构造方法。
     * <p>
     * 虚拟灵元不持有物理实例池（{@link #instancePool} 为 null），仅作为微内核治理流水线的路由切点与配置载体。
     * 架构约束：本构造方法受包级保护，必须由 {@link VirtualLingManager} 独占编排组装，外部无法脱离生命周期随意 new。
     *
     * @param lingId 灵元唯一标识
     * @param config 运行时治理配置
     * @param eventBus 事件总线，若提供则监听运行时状态变更
     * @param runtimeCoordinator 运行时状态协调器（状态真源）
     */
    LingRuntime(String lingId, LingRuntimeConfig config, EventBus eventBus,
                RuntimeCoordinator runtimeCoordinator) {
        this.lingId = Objects.requireNonNull(lingId, "lingId cannot be null");
        this.config = config != null ? config : LingRuntimeConfig.defaults();
        this.instancePool = null;
        this.runtimeCoordinator = Objects.requireNonNull(runtimeCoordinator, "RuntimeCoordinator is required");

        if (eventBus != null) {
            eventBus.subscribe(lingId, RuntimeStateChangedEvent.class, this::handleStateChanged);
        }
    }

    /**
     * 判断当前灵元是否为无物理实例池的虚拟灵元。
     *
     * @return 若为虚拟灵元返回 true，否则返回 false
     */
    public boolean isVirtual() {
        return instancePool == null;
    }

    private void handleStateChanged(RuntimeStateChangedEvent event) {
        if (event == null || event.getLingId() == null || !event.getLingId().equals(lingId)) {
            return;
        }
        RuntimeStatus newStatus = event.getTo();

        // 宏观运行时进入 STOPPING/REMOVED 后，灵核只需要同步收紧成员池写入。
        // 这里不反向写 RuntimeStatus，避免对象之间互相改写状态。
        if (newStatus == RuntimeStatus.STOPPING || newStatus == RuntimeStatus.REMOVED) {
            if (instancePool != null) {
                instancePool.shutdown();
            }
        }
    }

    public boolean isAvailable() {
        return currentStatus() == RuntimeStatus.ACTIVE &&
                (instancePool == null || instancePool.hasAvailableInstance());
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
    @Override
    public List<LingInstance> getReadyInstances() {
        if (instancePool == null) {
            return Collections.emptyList();
        }
        return instancePool.getActiveInstances().stream()
                .filter(LingInstance::isReady)
                .collect(Collectors.toList());
    }
}

