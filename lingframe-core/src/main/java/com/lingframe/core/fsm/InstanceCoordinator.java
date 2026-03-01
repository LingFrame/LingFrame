package com.lingframe.core.fsm;

import com.lingframe.core.ling.LingInstance;
import lombok.extern.slf4j.Slf4j;

/**
 * 驱动实例级微观状态机的协调器
 */
@Slf4j
public class InstanceCoordinator {

    public void prepare(LingInstance instance) {
        transitionState(instance, InstanceStatus.LOADING);
    }

    public void start(LingInstance instance) {
        transitionState(instance, InstanceStatus.STARTING);
    }

    public void markReady(LingInstance instance) {
        transitionState(instance, InstanceStatus.READY);
    }

    public void stop(LingInstance instance) {
        transitionState(instance, InstanceStatus.STOPPING);
    }

    public void error(LingInstance instance) {
        transitionState(instance, InstanceStatus.ERROR);
    }

    private void transitionState(LingInstance instance, InstanceStatus targetStatus) {
        try {
            instance.getStateMachine().transition(targetStatus);
            // 这里可以预留钩子，抛出状态变更事件
        } catch (IllegalStateTransitionException e) {
            log.error("Instance state transition failed: {} -> {}",
                    instance.getStateMachine().current(), targetStatus, e);
            throw e;
        }
    }

    /**
     * 执行单个实例的优雅关闭与拆卸，发布销毁事件以便底层系统清理资源
     */
    public void tearDown(LingInstance instance, com.lingframe.core.event.EventBus eventBus) {
        try {
            transitionState(instance, InstanceStatus.STOPPING);

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
            if (eventBus != null) {
                eventBus.publish(new com.lingframe.core.event.InstanceDestroyedEvent(
                        instance.getLingId(), instance.getVersion()));
            }

        } catch (Exception e) {
            log.error("Failed to tear down instance: {}", instance.getLingId(), e);
            transitionState(instance, InstanceStatus.ERROR);
        }
    }
}
