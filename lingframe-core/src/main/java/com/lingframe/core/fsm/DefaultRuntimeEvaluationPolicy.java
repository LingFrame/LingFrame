package com.lingframe.core.fsm;

import java.util.Collection;

/**
 * 默认运行时评估策略。
 * <p>
 * 聚合规则：
 * <pre>
 * ┌──────────────────────────────────────┬──────────────┐
 * │ 实例聚合条件                          │ 建议状态      │
 * ├──────────────────────────────────────┼──────────────┤
 * │ 无活跃实例（全部 DEAD 或集合为空）      │ INACTIVE     │
 * │ 有 READY 且错误率 < 阈值              │ ACTIVE       │
 * │ 有 READY 但错误率 ≥ 阈值              │ DEGRADED     │
 * │ 无 READY，但有 ERROR                 │ DEGRADED     │
 * │ 无 READY，全在 LOADING/STARTING      │ 保持 current │
 * └──────────────────────────────────────┴──────────────┘
 * </pre>
 */
public class DefaultRuntimeEvaluationPolicy implements RuntimeEvaluationPolicy {

    /**
     * 错误实例占活跃实例的比例阈值，超过则视为降级
     */
    private final double degradedThreshold;

    public DefaultRuntimeEvaluationPolicy() {
        this(0.5);
    }

    /**
     * @param degradedThreshold 降级阈值（0.0~1.0），错误实例占比 ≥ 此值时触发 DEGRADED
     */
    public DefaultRuntimeEvaluationPolicy(double degradedThreshold) {
        if (degradedThreshold < 0 || degradedThreshold > 1) {
            throw new IllegalArgumentException("degradedThreshold must be in [0.0, 1.0]");
        }
        this.degradedThreshold = degradedThreshold;
    }

    @Override
    public RuntimeStatus evaluate(RuntimeStatus current, Collection<InstanceStatus> instanceStates) {
        if (instanceStates == null || instanceStates.isEmpty()) {
            // 没有任何活跃实例
            return RuntimeStatus.INACTIVE;
        }

        long total = instanceStates.size();
        long ready = 0;
        long error = 0;

        for (InstanceStatus s : instanceStates) {
            if (s == InstanceStatus.READY) ready++;
            if (s == InstanceStatus.ERROR) error++;
        }

        // 有可用实例，根据错误率判断健康度
        if (ready > 0) {
            double errorRate = (double) error / total;
            return errorRate >= degradedThreshold
                    ? RuntimeStatus.DEGRADED
                    : RuntimeStatus.ACTIVE;
        }

        // 无可用实例但有异常实例 → 降级（可能自愈）
        if (error > 0) {
            return RuntimeStatus.DEGRADED;
        }

        // 所有实例都在启动中（CREATED / LOADING / STARTING），保持当前状态
        // 避免在启动瞬间抖动为 INACTIVE
        return current;
    }
}