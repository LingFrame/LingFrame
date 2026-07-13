package com.lingframe.core.ling;

import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.spi.RoutableTarget;

import java.util.Collections;
import java.util.List;

/**
 * 灵核极简路由目标。
 * <p>
 * 灵核（lingcore-app）作为进程级常驻 baseline，不持有 {@link RuntimeCoordinator} 状态机，
 * 不参与灵珑治理（timeout/rateLimit/maxConcurrent 对它无效），
 * 只提供 {@link RoutableTarget} 窄接口的最小实现让 Pipeline 能路由到它。
 * <p>
 * 关键约束：
 * <ul>
 *   <li>{@link #currentStatus()} 永远返回 {@link RuntimeStatus#ACTIVE}，使 {@code MacroStateGuardFilter} 守卫通过</li>
 *   <li>{@link #isAvailable()} 永远返回 true</li>
 *   <li>{@link #isCanaryTarget(LingInstance)} 永远返回 false（灵核只有单例，无 canary 概念）</li>
 *   <li>{@link #getReadyInstances()} 返回单例列表</li>
 * </ul>
 * <p>
 * 设计原则：不持有 Spring 类型（ApplicationContext/BeanFactory），
 * 不引入灵核装配时序耦合——灵核实例由外部传入。
 */
public class LingCoreRoutableTarget implements RoutableTarget {

    private final String lingId;
    private final LingInstance singletonInstance;

    public LingCoreRoutableTarget(String lingId, LingInstance singletonInstance) {
        this.lingId = lingId;
        this.singletonInstance = singletonInstance;
    }

    @Override
    public String getLingId() {
        return lingId;
    }

    @Override
    public RuntimeStatus currentStatus() {
        // 灵核不持有状态机，永远返回 ACTIVE 让守卫通过
        return RuntimeStatus.ACTIVE;
    }

    @Override
    public List<LingInstance> getReadyInstances() {
        // 灵核只有一个进程级实例
        return singletonInstance != null
                ? Collections.singletonList(singletonInstance)
                : Collections.emptyList();
    }

    @Override
    public boolean isAvailable() {
        // 灵核永远可用（进程级常驻）
        return true;
    }

    @Override
    public boolean isCanaryTarget(LingInstance target) {
        // 灵核只有单例，无 canary 概念
        return false;
    }
}
