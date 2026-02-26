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
}
