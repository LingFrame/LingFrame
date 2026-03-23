package com.lingframe.core.fsm;

import java.util.Collection;

/**
 * 运行时状态聚合评估策略。
 * <p>
 * 将多个实例的微观状态聚合为一个宏观 {@link RuntimeStatus}。
 * 不同业务场景可提供不同实现（如更激进的降级策略、自定义阈值等）。
 */
@FunctionalInterface
public interface RuntimeEvaluationPolicy {

    /**
     * 基于当前运行时状态与所有活跃实例的状态快照，评估建议的运行时状态。
     *
     * @param current        当前运行时状态
     * @param instanceStates 该 Ling 下所有活跃实例的状态集合（不含已 DEAD 的）
     * @return 建议的目标运行时状态
     */
    RuntimeStatus evaluate(RuntimeStatus current, Collection<InstanceStatus> instanceStates);
}