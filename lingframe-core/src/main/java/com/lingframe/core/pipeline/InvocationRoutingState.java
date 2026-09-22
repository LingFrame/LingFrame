package com.lingframe.core.pipeline;

import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.routing.LingVersionPolicy;
import lombok.Getter;
import lombok.Setter;
import com.lingframe.core.routing.RoutingDecision;

/**
 * 路由阶段协议分区。
 * 只描述"选中了哪个目标实例"，不承载类型解析和治理决策。
 */
@Getter
@Setter
public class InvocationRoutingState {

    /**
     * 路由得到的目标实例。
     * 这是单次调用内的短生命周期强引用，reset() 时必须物理断开。
     */
    private LingInstance targetInstance;

    /**
     * 是否由入口提前指定目标实例。
     * 例如灵核侧网关、特殊测试或未来的精确回放入口。
     */
    private boolean preResolved;

    /** 本次局部版本选路固定使用的策略，不在后续阶段重新读取最新修订。 */
    private LingVersionPolicy lingVersionPolicy;

    /** 本次调用最终可解释的版本与实例选路事实。 */
    private RoutingDecision routingDecision;

    private String policyRevision;
    private String routingReason;

    void reset() {
        this.targetInstance = null;
        this.preResolved = false;
        this.lingVersionPolicy = null;
        this.routingDecision = null;
        this.policyRevision = null;
        this.routingReason = null;
    }

    void copyFrom(InvocationRoutingState source) {
        if (source == null) {
            return;
        }
        this.targetInstance = source.targetInstance;
        this.preResolved = source.preResolved;
        this.lingVersionPolicy = source.lingVersionPolicy;
        this.routingDecision = source.routingDecision;
        this.policyRevision = source.policyRevision;
        this.routingReason = source.routingReason;
    }
}
