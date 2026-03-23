package com.lingframe.core.pipeline;

import com.lingframe.core.ling.LingInstance;
import lombok.Getter;
import lombok.Setter;

/**
 * 路由阶段协议分区。
 * 只描述“选中了哪个目标实例”，不承载类型解析和治理决策。
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

    void reset() {
        this.targetInstance = null;
        this.preResolved = false;
    }

    void copyFrom(InvocationRoutingState source) {
        if (source == null) {
            return;
        }
        this.targetInstance = source.targetInstance;
        this.preResolved = source.preResolved;
    }
}
