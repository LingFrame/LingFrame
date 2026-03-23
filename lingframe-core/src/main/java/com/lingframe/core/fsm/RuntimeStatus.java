package com.lingframe.core.fsm;

import java.util.*;

/**
 * 运行时级服务可用性状态。
 * <p>
 * 描述一个 Ling 在运行时维度的宏观健康度，
 * 由底层多个 {@link InstanceStatus} 的聚合评估驱动（见 RuntimeCoordinator）。
 * <p>
 * 注意：当前版本里它同时承担了两类语义：
 * 1. 宏观可用性事实：INACTIVE / ACTIVE / DEGRADED
 * 2. 运维生命周期意图：STOPPING / REMOVED
 * 因此 STOPPING / REMOVED 在实现里具有更高优先级，会压制后续聚合评估。
 */
public enum RuntimeStatus {

    /**
     * 已注册但无可用实例
     */
    INACTIVE,
    /**
     * 正常服役（含灰度期间）
     */
    ACTIVE,
    /**
     * 降级：健康检查失败 / 熔断触发，可自愈回 ACTIVE
     */
    DEGRADED,
    /**
     * 优雅关闭中，排空存量请求
     */
    STOPPING,
    /**
     * 已移除，不可恢复（终态）
     */
    REMOVED;

    /**
     * 合法转换表（不可变）
     */
    public static final Map<RuntimeStatus, Set<RuntimeStatus>> TRANSITIONS;

    static {
        Map<RuntimeStatus, Set<RuntimeStatus>> m = new EnumMap<>(RuntimeStatus.class);
        m.put(INACTIVE, EnumSet.of(ACTIVE, REMOVED));
        m.put(ACTIVE, EnumSet.of(DEGRADED, STOPPING, INACTIVE));
        m.put(DEGRADED, EnumSet.of(ACTIVE, STOPPING, INACTIVE));
        m.put(STOPPING, EnumSet.of(REMOVED));
        m.put(REMOVED, Collections.emptySet());       // 终态
        TRANSITIONS = Collections.unmodifiableMap(m);
    }

    /**
     * 创建以 {@link #INACTIVE} 为初始状态的运行时级状态机
     *
     * @param lingId Ling 标识
     */
    public static StateMachine<RuntimeStatus> newMachine(String lingId) {
        return new StateMachine<>(lingId, INACTIVE, TRANSITIONS);
    }
}
