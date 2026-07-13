package com.lingframe.core.pipeline;

import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.ProviderKind;
import lombok.Getter;
import lombok.Setter;

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

    /**
     * 选中 provider 的类型标记。
     * <p>
     * 由 {@code ContractProviderRoutingFilter} 在 L0 阶段写入，
     * 供观测和 Dashboard 区分流量来源（CORE baseline vs LING 灵元）。
     * 旧格式 FQSID 不经过 provider 路由时为 null。
     */
    private ProviderKind providerKind;

    void reset() {
        this.targetInstance = null;
        this.preResolved = false;
        this.providerKind = null;
    }

    void copyFrom(InvocationRoutingState source) {
        if (source == null) {
            return;
        }
        this.targetInstance = source.targetInstance;
        this.preResolved = source.preResolved;
        this.providerKind = source.providerKind;
    }
}
