package com.lingframe.core.ling;

/**
 * 契约提供方类型。
 * <p>
 * 区分灵核（进程级常驻 baseline）与灵元（可热替换），
 * 用于 L0 provider 级路由的权重默认值与流量切分判断。
 * <p>
 * ⚠️ 这是类型标记，不是行为标签——能力的差异由 {@code RoutableTarget} 的实现类型
 * （{@code LingRuntime} vs {@code LingCoreRoutableTarget}）承载，
 * 本枚举只用于注册时声明身份，不用于运行期行为分发。
 */
public enum ProviderKind {
    /** 灵核，进程级常驻，多 provider 场景默认承接全量流量（weight=100） */
    CORE,

    /** 灵元，可热替换，多 provider 场景默认不接流量（weight=0，需 Dashboard 显式配置） */
    LING
}
