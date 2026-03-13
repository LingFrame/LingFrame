package com.lingframe.core.fsm;

import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.InstanceDestroyedEvent;
import com.lingframe.core.event.InstanceStateChangedEvent;
import com.lingframe.core.exception.IllegalStateTransitionException;
import lombok.extern.slf4j.Slf4j;

/**
 * 驱动实例级微观状态机的协调器
 */
@Slf4j
public class InstanceCoordinator {

    private final EventBus eventBus;

    /**
     * @param eventBus 全局事件总线（可选，为 null 时不发布状态变更事件）
     */
    public InstanceCoordinator(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public void prepare(LingInstance instance) {
        transitionState(instance, InstanceStatus.LOADING);
    }

    public void start(LingInstance instance) {
        transitionState(instance, InstanceStatus.STARTING);
    }

    public void markReady(LingInstance instance) {
        transitionState(instance, InstanceStatus.READY);
    }

    public void pause(LingInstance instance) {
        transitionState(instance, InstanceStatus.INACTIVE);
    }

    public void resume(LingInstance instance) {
        transitionState(instance, InstanceStatus.READY);
    }

    public void stop(LingInstance instance) {
        transitionState(instance, InstanceStatus.STOPPING);
    }

    public void error(LingInstance instance) {
        transitionState(instance, InstanceStatus.ERROR);
    }

    private void transitionState(LingInstance instance, InstanceStatus targetStatus) {
        InstanceStatus fromStatus = instance.getStateMachine().current();
        if (fromStatus == targetStatus) {
            return;
        }
        try {
            instance.getStateMachine().transition(targetStatus);

            // 发布状态变更事件（供 Dashboard 感知、监控告警、审计追踪等）
            if (eventBus != null) {
                eventBus.publish(new InstanceStateChangedEvent(
                        instance.getLingId(), instance.getVersion(),
                        fromStatus, targetStatus));
            }
        } catch (IllegalStateTransitionException e) {
            log.error("Instance state transition failed: {} -> {}",
                    fromStatus, targetStatus, e);
            throw e;
        }
    }

    /**
     * 执行单个实例的优雅关闭与拆卸，发布销毁事件以便底层系统清理资源
     */
    public void tearDown(LingInstance instance, EventBus eventBus) {
        // tearDown 使用传入的 eventBus 发布销毁事件，保持向后兼容
        EventBus bus = eventBus != null ? eventBus : this.eventBus;
        try {
            if (instance.isDestroyed()) {
                return;
            }
            if (!instance.isDying()) {
                transitionState(instance, InstanceStatus.STOPPING);
            }

            // 通知容器关闭（清理 Spring/资源等）
            if (instance.getContainer() != null) {
                try {
                    instance.getContainer().stop();
                } catch (Exception e) {
                    log.error("Failed to stop container for instance: {}", instance.getLingId(), e);
                }
            }

            transitionState(instance, InstanceStatus.DEAD);

            // 触发大管家的扫尾收割清理
            if (bus != null) {
                bus.publish(new InstanceDestroyedEvent(
                        instance.getLingId(), instance.getVersion()));
            }

        } catch (Exception e) {
            log.error("Failed to tear down instance: {}", instance.getLingId(), e);
            try {
                if (instance.getStateMachine().current() != InstanceStatus.ERROR &&
                        instance.getStateMachine().current() != InstanceStatus.DEAD) {
                    transitionState(instance, InstanceStatus.ERROR);
                }
            } catch (Exception ignore) {
                log.warn("Could not transition to ERROR state after tearDown failure: {}", ignore.getMessage());
            }
        }
    }
}
