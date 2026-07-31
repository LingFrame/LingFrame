package com.lingframe.core.spi;

import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.ling.LingInstance;

import java.util.List;

/**
 * Pipeline 路由/调用所需的最小契约。
 * <p>
 * 路由升维后，Pipeline 不再直接依赖 {@code LingRuntime} 具体类，
 * 而是依赖此窄接口——灵元返回 {@code LingRuntime}（实现本接口），
 * 灵核返回 {@code LingCoreRoutableTarget}（极简实现）。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>方法名跟随 {@code LingRuntime} 既有命名（如 {@code currentStatus()} 而非 {@code getStatus()}），
 *       使 {@code LingRuntime implements RoutableTarget} 零逻辑改动</li>
 *   <li>灵核与灵元统一抽象，
 *       避免 Pipeline 直接调 {@code LingRuntime.getInstancePool()} 等 LingRuntime 独有方法</li>
 * </ul>
 */
public interface RoutableTarget {

    /**
     * 灵元/灵核标识。
     */
    String getLingId();

    /**
     * 运行时宏观状态。
     * <p>
     * 灵元返回 {@link RuntimeCoordinator} 管理的真实状态；
     * 灵核永远返回 {@link RuntimeStatus#ACTIVE}（不持有状态机）。
     * <p>
     * 方法名跟随 {@code LingRuntime.currentStatus()} 既有命名。
     */
    RuntimeStatus currentStatus();

    /**
     * 获取所有 READY 状态实例（用于路由选择）。
     * <p>
     * 灵元返回 InstancePool 中所有 ready 实例；
     * 灵核永远返回单例列表（只有一个进程级实例）。
     */
    List<LingInstance> getReadyInstances();

    /**
     * 是否可用。
     * <p>
     * 灵元检查 {@code currentStatus() == ACTIVE && instancePool.hasAvailableInstance()}；
     * 灵核永远返回 true。
     */
    boolean isAvailable();
}

