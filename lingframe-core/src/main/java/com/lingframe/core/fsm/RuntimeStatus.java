package com.lingframe.core.fsm;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 运行时级服务可用性状态。
 * <p>
 * 描述一个 Ling 在运行时维度的宏观健康度，
 * 由底层多个 {@link InstanceStatus} 的聚合评估驱动（见 RuntimeCoordinator）。
 * <p>
 * 状态基于 {@link Kind} 进行类型自描述：
 * <ul>
 *   <li>{@link Kind#FACT}：宏观可用性事实（INACTIVE / ACTIVE / DEGRADED），由实例健康度事实聚合推算</li>
 *   <li>{@link Kind#INTENT}：运维受控意图（RECOVERING / STOPPING），由外部控制面显式发起，压制微观聚合评估</li>
 *   <li>{@link Kind#TERMINAL}：终态（REMOVED），资源释放完毕</li>
 * </ul>
 * 意图态与终态可通过 {@link #suppressesEvaluation()} 统一判定，阻止微观事件对宏观受控状态的反向覆盖。
 */
public enum RuntimeStatus {

    /**
     * 已注册但无可用实例
     */
    INACTIVE(Kind.FACT),
    /**
     * 正常服役（含灰度期间）
     */
    ACTIVE(Kind.FACT),
    /**
     * 降级：健康检查失败 / 熔断触发，可自愈回 ACTIVE
     */
    DEGRADED(Kind.FACT),
    /**
     * 受控恢复中。
     * 表示运维已触发恢复链路，实例层或治理层正在收敛回稳定态。
     */
    RECOVERING(Kind.INTENT),
    /**
     * 优雅关闭中，排空存量请求
     */
    STOPPING(Kind.INTENT),
    /**
     * 已移除，不可恢复（终态）
     */
    REMOVED(Kind.TERMINAL);

    /**
     * 状态性质分类
     */
    public enum Kind {
        /**
         * 宏观聚合事实：由底层实例健康度聚合计算驱动
         */
        FACT,
        /**
         * 运维意图态：由控制面/运维显式触发受控过程，压制微观聚合评估
         */
        INTENT,
        /**
         * 终态：资源已释放，不可逆
         */
        TERMINAL
    }

    private final Kind kind;

    RuntimeStatus(Kind kind) {
        this.kind = kind;
    }

    /**
     * 获取状态性质分类
     *
     * @return 状态性质
     */
    public Kind kind() {
        return this.kind;
    }

    /**
     * 是否压制微观聚合评估。
     * 当灵元处于运维意图态或终态时，禁止微观实例事实变动覆盖宏观状态。
     *
     * @return 若为非 FACT 类状态返回 true
     */
    public boolean suppressesEvaluation() {
        return this.kind != Kind.FACT;
    }

    /**
     * 合法转换表（不可变：外层 Map 与每个 value Set 均不可变，防止运行期篡改状态机）。
     */
    public static final Map<RuntimeStatus, Set<RuntimeStatus>> TRANSITIONS;

    static {
        Map<RuntimeStatus, Set<RuntimeStatus>> m = new EnumMap<>(RuntimeStatus.class);
        m.put(INACTIVE, Collections.unmodifiableSet(EnumSet.of(ACTIVE, DEGRADED, RECOVERING, REMOVED)));
        m.put(ACTIVE, Collections.unmodifiableSet(EnumSet.of(DEGRADED, RECOVERING, STOPPING, INACTIVE)));
        m.put(DEGRADED, Collections.unmodifiableSet(EnumSet.of(ACTIVE, RECOVERING, STOPPING, INACTIVE)));
        m.put(RECOVERING, Collections.unmodifiableSet(EnumSet.of(ACTIVE, DEGRADED, INACTIVE, STOPPING)));
        m.put(STOPPING, Collections.unmodifiableSet(EnumSet.of(REMOVED)));
        m.put(REMOVED, Collections.unmodifiableSet(Collections.emptySet()));       // 终态
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

