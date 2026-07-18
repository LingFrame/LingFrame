package com.lingframe.core.ling;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.InstanceDestroyedEvent;
import com.lingframe.core.event.InstanceStateChangedEvent;
import com.lingframe.core.exception.IllegalStateTransitionException;
import com.lingframe.core.fsm.InstanceStatus;
import com.lingframe.core.fsm.TransitionResult;
import lombok.extern.slf4j.Slf4j;

/**
 * 实例级状态机协同器。
 * <p>
 * 它是实例状态的唯一正式写入口，负责：
 * <ul>
 *   <li>驱动实例状态转换</li>
 *   <li>处理 CAS 冲突重试</li>
 *   <li>发布实例状态事件</li>
 *   <li>统一执行销毁收尾</li>
 * </ul>
 * 它拥有“写权限”，但不拥有实例对象本身；
 * {@link LingInstance} 内部保留状态机作为一致性载体，
 * 由本协调器独占驱动。
 */
@Slf4j
final class InstanceCoordinator {

    private static final int MAX_CAS_RETRIES = 3;

    private final EventBus eventBus;

    private final LingInstanceTerminator terminator;

    public InstanceCoordinator(EventBus eventBus) {
        this(eventBus, new LingInstanceTerminator());
    }

    public InstanceCoordinator(EventBus eventBus, LingInstanceTerminator terminator) {
        this.eventBus = eventBus;
        this.terminator = terminator;
    }

    public void prepare(LingInstance instance) {
        doTransition(instance, InstanceStatus.LOADING);
    }

    public void start(LingInstance instance) {
        doTransition(instance, InstanceStatus.STARTING);
    }

    public void markReady(LingInstance instance) {
        doTransition(instance, InstanceStatus.READY);
    }

    public void stop(LingInstance instance) {
        doTransition(instance, InstanceStatus.STOPPING);
    }

    public void error(LingInstance instance) {
        doTransition(instance, InstanceStatus.ERROR);
    }

    public void recovering(LingInstance instance) {
        doTransition(instance, InstanceStatus.RECOVERING);
    }

    private void doTransition(LingInstance instance, InstanceStatus target) {
        doTransition(instance, target, snapshotIdentity(instance));
    }

    private void doTransition(LingInstance instance, InstanceStatus target, InstanceIdentity identity) {
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            TransitionResult<InstanceStatus> result = instance.transitionState(target);

            switch (result.code()) {
                case SUCCESS:
                    if (result.from() != result.target()) {
                        publishStateChanged(identity, result.from(), result.target());
                    }
                    return;

                case CONFLICT:
                    log.warn("CAS conflict on instance [{}], attempt {}/{}, current={}",
                            instance.getLingId(), attempt + 1, MAX_CAS_RETRIES, instance.currentStatus());
                    break;

                case ILLEGAL:
                    throw new IllegalStateTransitionException(result.from(), result.target());

                default:
                    throw new AssertionError("Unknown transition code: " + result.code());
            }
        }

        throw new IllegalStateException(String.format(
                "Failed to transition instance [%s] to %s after %d CAS retries, current=%s",
                instance.getLingId(), target, MAX_CAS_RETRIES, instance.currentStatus()));
    }

    public void tearDown(LingInstance instance) {
        if (instance.currentStatus() == InstanceStatus.DEAD) {
            return;
        }

        InstanceIdentity identity = snapshotIdentity(instance);
        try {
            driveToStopping(instance);
            terminator.terminate(instance);
            doTransition(instance, InstanceStatus.DEAD, identity);
            publishDestroyed(identity);
        } catch (Exception e) {
            if (instance.currentStatus() == InstanceStatus.DEAD) {
                log.debug("Instance [{}] already torn down by another thread", instance.getLingId());
                return;
            }
            log.error("Failed to tear down instance [{}]", instance.getLingId(), e);
            tryTransitionToError(instance);
        }
    }

    private void driveToStopping(LingInstance instance) {
        if (instance.currentStatus() == InstanceStatus.STOPPING) {
            return;
        }
        try {
            doTransition(instance, InstanceStatus.STOPPING);
        } catch (IllegalStateTransitionException e) {
            log.info("Cannot reach STOPPING directly from {}, routing through ERROR", instance.currentStatus());
            doTransition(instance, InstanceStatus.ERROR);
            doTransition(instance, InstanceStatus.STOPPING);
        }
    }

    private void tryTransitionToError(LingInstance instance) {
        InstanceStatus current = instance.currentStatus();
        if (current == InstanceStatus.DEAD || current == InstanceStatus.ERROR) {
            return;
        }
        try {
            doTransition(instance, InstanceStatus.ERROR);
        } catch (Exception ex) {
            log.warn("Could not transition instance [{}] to ERROR: {}",
                    instance.getLingId(), ex.getMessage());
        }
    }

    private void publishStateChanged(InstanceIdentity identity, InstanceStatus from, InstanceStatus to) {
        if (eventBus == null) {
            return;
        }
        log.debug("Instance [{}] id={} v{} state changed: {} -> {}",
                identity.lingId, identity.instanceId, identity.version, from, to);
        eventBus.publish(new InstanceStateChangedEvent(
                identity.lingId, identity.instanceId, identity.version, from, to));
    }

    private void publishDestroyed(InstanceIdentity identity) {
        if (eventBus == null) {
            return;
        }
        log.info("Instance [{}] id={} v{} destroyed", identity.lingId, identity.instanceId, identity.version);
        eventBus.publish(new InstanceDestroyedEvent(identity.lingId, identity.instanceId, identity.version));
    }

    private InstanceIdentity snapshotIdentity(LingInstance instance) {
        return new InstanceIdentity(instance.getLingId(), instance.getInstanceId(), instance.getVersion());
    }

    private static final class InstanceIdentity {
        private final String lingId;
        private final String instanceId;
        private final String version;

        private InstanceIdentity(String lingId, String instanceId, String version) {
            this.lingId = lingId;
            this.instanceId = instanceId;
            this.version = version;
        }
    }
}
