package com.lingframe.core.ling;

import com.lingframe.core.spi.RoutableTarget;

import java.util.Collection;

/**
 * LingRepository 负责组件（LingRuntime / 灵核路由目标）的存储和检索。
 * <p>
 * 路由升维：runtimes map 类型为 {@code Map<String, RoutableTarget>}，
 * 灵元（{@link LingRuntime}）和灵核（{@link LingCoreRoutableTarget}）都进同一 map。
 * <p>
 * 生命周期语义统一使用 register / unregister，与 {@code RuntimeCoordinator.unregister}
 * 及 HotSwap 注销命名对齐；禁止使用 deregister 等非常用/歧义动词。
 */
public interface LingRepository {

    /**
     * 将一个新的 LingRuntime 注册到仓储中。
     *
     * @param runtime 要注册的灵元运行时聚合根
     */
    void register(LingRuntime runtime);

    /**
     * 注销并移除指定的 LingRuntime。
     *
     * @param lingId 组件的唯一标识
     * @return 若找到并成功移除则返回该聚合根，否则为 null
     */
    LingRuntime unregister(String lingId);

    /**
     * 根据组件 ID 获取对应的 LingRuntime。
     * 灵核路由目标不由此方法返回（返回 null），请用 {@link #getRoutableTarget(String)}。
     *
     * @param lingId 组件 ID
     * @return 对应的 LingRuntime，不存在或为灵核时返回 null
     */
    LingRuntime getRuntime(String lingId);

    /**
     * 检查组件是否存在（含灵元与灵核路由目标）
     */
    boolean hasRuntime(String lingId);

    /**
     * 获取所有已注册的灵元运行时（不含灵核路由目标）。
     */
    Collection<LingRuntime> getAllRuntimes();

    /**
     * 获取路由目标（灵元返回 LingRuntime，灵核返回 LingCoreRoutableTarget）。
     * <p>
     * 路由层主入口。
     *
     * @param lingId 灵元/灵核 ID
     * @return 路由目标；不存在返回 null
     */
    RoutableTarget getRoutableTarget(String lingId);

    /**
     * 注册路由目标（灵元和灵核都可用此方法）。
     * 灵元也可使用 {@link #register(LingRuntime)}。
     * 幂等：同一 lingId 重复注册覆盖旧值。
     *
     * @param target 路由目标
     */
    void registerRoutableTarget(RoutableTarget target);

    /**
     * 注销并移除路由目标。
     *
     * @param lingId 灵元/灵核 ID
     * @return 被移除的路由目标；不存在返回 null
     */
    RoutableTarget unregisterRoutableTarget(String lingId);
}
