package com.lingframe.core.fsm;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.InstanceDestroyedEvent;
import com.lingframe.core.event.InstanceStateChangedEvent;
import com.lingframe.core.exception.IllegalStateTransitionException;
import com.lingframe.core.ling.LingInstance;
import lombok.extern.slf4j.Slf4j;

/**
 * 实例级状态机协同器
 * <p>
 * 封装 {@link StateMachine} 的调用细节，统一负责：
 * <ul>
 *   <li>CAS 竞争重试</li>
 *   <li>领域事件发布（状态变更 / 实例销毁）</li>
 *   <li>优雅关闭与资源拆卸（{@link #tearDown}）</li>
 * </ul>
 * <b>事件一致性保证</b>：仅在 CAS 成功后，使用结果中的 from/target 快照发布事件，
 * 杜绝“幽灵事件”。
 */
@Slf4j
public class InstanceCoordinator {

    /**
     * CAS 竞争最大重试次数，超过视为异常并抛出
     */
    private static final int MAX_CAS_RETRIES = 3;

    /**
     * 事件总线（可选，为 null 时不发布事件）
     */
    private final EventBus eventBus;

    /**
     * @param eventBus 全局事件总线，为 null 时不发布任何事件
     */
    public InstanceCoordinator(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    /* ==================== 生命周期驱动方法 ==================== */

    /**
     * CREATED -> LOADING：开始加载字节码与校验
     */
    public void prepare(LingInstance instance) {
        doTransition(instance, InstanceStatus.LOADING);
    }

    /**
     * LOADING -> STARTING：拉起 Spring Context
     */
    public void start(LingInstance instance) {
        doTransition(instance, InstanceStatus.STARTING);
    }

    /**
     * STARTING -> READY：可接受流量
     */
    public void markReady(LingInstance instance) {
        doTransition(instance, InstanceStatus.READY);
    }

    /**
     * any -> STOPPING：开始优雅关闭
     */
    public void stop(LingInstance instance) {
        doTransition(instance, InstanceStatus.STOPPING);
    }

    /**
     * any -> ERROR：标记异常
     */
    public void error(LingInstance instance) {
        doTransition(instance, InstanceStatus.ERROR);
    }

    /* ==================== 核心跃迁逻辑 ==================== */

    /**
     * 执行状态跃迁，含 CAS 重试与事件发布。
     *
     * @param instance 目标实例
     * @param target   期望到达的状态
     * @throws IllegalStateTransitionException 转换表中不存在此路径
     * @throws IllegalStateException           CAS 重试耗尽
     */
    private void doTransition(LingInstance instance, InstanceStatus target) {
        StateMachine<InstanceStatus> fsm = instance.getStateMachine();

        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            TransitionResult<InstanceStatus> result = fsm.transition(target);

            switch (result.code()) {
                case SUCCESS:
                    // 幂等调用（from == target）不发布事件
                    if (result.from() != result.target()) {
                        publishStateChanged(instance, result.from(), result.target());
                    }
                    return;

                case CONFLICT:
                    // 并发竞争，日志后重试
                    log.warn("CAS conflict on instance [{}], attempt {}/{}, current={}",
                            instance.getLingId(), attempt + 1, MAX_CAS_RETRIES, fsm.current());
                    break;

                case ILLEGAL:
                    // 编程错误：转换表中不存在该路径
                    throw new IllegalStateTransitionException(result.from(), result.target());

                default:
                    throw new AssertionError("Unknown transition code: " + result.code());
            }
        }

        // CAS 重试次数耗尽，说明存在异常级别的并发竞争
        throw new IllegalStateException(String.format(
                "Failed to transition instance [%s] to %s after %d CAS retries, current=%s",
                instance.getLingId(), target, MAX_CAS_RETRIES, fsm.current()));
    }

    /* ==================== 优雅关闭与资源拆卸 ==================== */

    /**
     * 优雅关闭并拆卸单个实例。
     * <p>
     * 完整流程：
     * <ol>
     *   <li>驱动到 STOPPING（早期状态借道 ERROR）</li>
     *   <li>关闭容器（Spring Context / 线程池等）</li>
     *   <li>跃迁到 DEAD 终态</li>
     *   <li>发布销毁事件，通知大管家做扫尾清理</li>
     * </ol>
     *
     * @param instance 待拆卸实例
     */
    public void tearDown(LingInstance instance) {
        StateMachine<InstanceStatus> fsm = instance.getStateMachine();

        // 已处于终态，幂等返回
        if (fsm.current() == InstanceStatus.DEAD) {
            return;
        }

        try {
            driveToStopping(instance);

            // 关闭容器
            if (instance.getContainer() != null) {
                instance.getContainer().stop();
            }

            // 跃迁到终态
            doTransition(instance, InstanceStatus.DEAD);

            // 通知清理
            publishDestroyed(instance);

        } catch (Exception e) {
            // 另一线程已完成拆卸，静默退出
            if (fsm.current() == InstanceStatus.DEAD) {
                log.debug("Instance [{}] already torn down by another thread",
                        instance.getLingId());
                return;
            }
            log.error("Failed to tear down instance [{}]", instance.getLingId(), e);
            tryTransitionToError(instance);
        }
    }

    /**
     * 驱动实例进入 STOPPING 状态。
     * <p>
     * READY / ERROR 可直接到达 STOPPING；
     * 早期状态（CREATED / LOADING / STARTING）需先借道 ERROR 再到 STOPPING。
     */
    private void driveToStopping(LingInstance instance) {
        if (instance.getStateMachine().current() == InstanceStatus.STOPPING) {
            return;
        }
        try {
            // 尝试直接跃迁（适用于 READY、ERROR）
            doTransition(instance, InstanceStatus.STOPPING);
        } catch (IllegalStateTransitionException e) {
            // 当前状态无法直接到 STOPPING，借道 ERROR
            log.info("Cannot reach STOPPING directly from {}, routing through ERROR",
                    instance.getStateMachine().current());
            doTransition(instance, InstanceStatus.ERROR);
            doTransition(instance, InstanceStatus.STOPPING);
        }
    }

    /**
     * 尽力跃迁到 ERROR 状态（兜底操作，不抛异常）
     */
    private void tryTransitionToError(LingInstance instance) {
        InstanceStatus cur = instance.getStateMachine().current();
        // 已在终态或异常态，无需操作
        if (cur == InstanceStatus.DEAD || cur == InstanceStatus.ERROR) {
            return;
        }
        try {
            doTransition(instance, InstanceStatus.ERROR);
        } catch (Exception ex) {
            log.warn("Could not transition instance [{}] to ERROR: {}",
                    instance.getLingId(), ex.getMessage());
        }
    }

    /* ==================== 事件发布（单一出口）==================== */

    /**
     * 发布实例状态变更事件
     */
    private void publishStateChanged(LingInstance instance,
                                     InstanceStatus from, InstanceStatus to) {
        if (eventBus == null) {
            return;
        }
        log.debug("Instance [{}] v{} state changed: {} -> {}",
                instance.getLingId(), instance.getVersion(), from, to);
        eventBus.publish(new InstanceStateChangedEvent(
                instance.getLingId(), instance.getVersion(), from, to));
    }

    /**
     * 发布实例销毁事件
     */
    private void publishDestroyed(LingInstance instance) {
        if (eventBus == null) {
            return;
        }
        log.info("Instance [{}] v{} destroyed",
                instance.getLingId(), instance.getVersion());
        eventBus.publish(new InstanceDestroyedEvent(
                instance.getLingId(), instance.getVersion()));
    }
}
