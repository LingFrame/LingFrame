package com.lingframe.core.ling;

import com.lingframe.core.spi.RoutableTarget;

import java.util.Collection;

/**
 * LingRepository 负责组件（LingRuntime / 灵核路由目标）的存储和检索。
 * <p>
 * 路由升维：runtimes map 类型升级为 {@code Map<String, RoutableTarget>}，
 * 灵元（{@link LingRuntime}）和灵核（{@link LingCoreRoutableTarget}）都进同一 map。
 * 旧方法 {@link #register(LingRuntime)} / {@link #getRuntime(String)} 保留兼容性，
 * 内部委托新方法，老调用方（dashboard 等）零改动。
 */
public interface LingRepository {

    /**
     * 将一个新的 LingRuntime 注册到仓储中。
     *
     * @param runtime 要注册的Ling运行时聚合根
     */
    void register(LingRuntime runtime);

    /**
     * 移除指定的 LingRuntime。
     *
     * @param lingId 组件的唯一标识
     * @return 如果找到并成功移除则返回该聚合根，否则为 null
     */
    LingRuntime deregister(String lingId);

    /**
     * 根据组件 ID 获取对应的 LingRuntime。
     *
     * @param lingId 组件 ID
     * @return 对应的 LingRuntime
     */
    LingRuntime getRuntime(String lingId);

    /**
     * 检查组件是否存在
     */
    boolean hasRuntime(String lingId);

    /**
     * 获取存储的所有被激活的组件。
     */
    Collection<LingRuntime> getAllRuntimes();

    /**
     * 路由升维：获取路由目标（灵元返回 LingRuntime，灵核返回 LingCoreRoutableTarget）。
     * <p>
     * 路由层主入口，替代 {@link #getRuntime(String)}。
     *
     * @param lingId 灵元/灵核 ID
     * @return 路由目标；不存在返回 null
     */
    RoutableTarget getRoutableTarget(String lingId);

    /**
     * 路由升维：注册路由目标（灵元和灵核都用此方法注册）。
     * <p>
     * 灵元注册时优先使用 {@link #register(LingRuntime)}，本方法主要供灵核使用。
     * 幂等：同一 lingId 重复注册覆盖旧值。
     *
     * @param target 路由目标
     */
    void registerRoutableTarget(RoutableTarget target);

    /**
     * 路由升维：移除路由目标。
     *
     * @param lingId 灵元/灵核 ID
     * @return 被移除的路由目标；不存在返回 null
     */
    RoutableTarget deregisterRoutableTarget(String lingId);
}
