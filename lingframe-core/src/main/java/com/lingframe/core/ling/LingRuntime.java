package com.lingframe.core.ling;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.RuntimeStateChangedEvent;
import com.lingframe.core.fsm.InstanceCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.fsm.StateMachine;
import lombok.Getter;
import lombok.ToString;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 灵元运行时
 * 仅承担状态、数据与多版本实例池的宿主职责，剥离生命周期、路由与调用逻辑。
 */
@ToString
public class LingRuntime {

    @Getter
    private final String lingId;

    @Getter
    private final LingRuntimeConfig config;

    @Getter
    private final InstancePool instancePool;

    @Getter
    private final StateMachine<RuntimeStatus> stateMachine;

    // 流量统计
    @Getter
    private final AtomicLong totalRequests = new AtomicLong(0);
    @Getter
    private final AtomicLong stableRequests = new AtomicLong(0);
    @Getter
    private final AtomicLong canaryRequests = new AtomicLong(0);
    @Getter
    private final AtomicLong activeRequests = new AtomicLong(0);
    @Getter
    private volatile long statsWindowStart = System.currentTimeMillis();

    @Getter
    private final long installedAt = System.currentTimeMillis();

    public LingRuntime(String lingId, LingRuntimeConfig config, EventBus eventBus) {
        this(lingId, config, eventBus, null);
    }

    public LingRuntime(String lingId, LingRuntimeConfig config, EventBus eventBus,
            InstanceCoordinator instanceCoordinator) {
        this.lingId = lingId;
        this.config = config != null ? config : LingRuntimeConfig.defaults();
        this.instancePool = new InstancePool(lingId, this.config.getMaxHistorySnapshots());
        this.instancePool.setInstanceCoordinator(instanceCoordinator);
        this.stateMachine = RuntimeStatus.newMachine(lingId);
        if (eventBus != null) {
            eventBus.subscribe(lingId, RuntimeStateChangedEvent.class, this::handleStateChanged);
        }
    }

    private void handleStateChanged(RuntimeStateChangedEvent event) {
        if (event == null || event.getLingId() == null || !event.getLingId().equals(lingId)) {
            return;
        }
        RuntimeStatus current = event.getTo();
        if (current == RuntimeStatus.STOPPING || current == RuntimeStatus.REMOVED) {
            instancePool.shutdown();
        }
    }

    public void recordRequest(boolean isCanary) {
        totalRequests.incrementAndGet();
        if (isCanary) {
            canaryRequests.incrementAndGet();
        } else {
            stableRequests.incrementAndGet();
        }
    }

    public void resetTrafficStats() {
        totalRequests.set(0);
        stableRequests.set(0);
        canaryRequests.set(0);
        activeRequests.set(0);
        statsWindowStart = System.currentTimeMillis();
    }

    public void startRequest() {
        activeRequests.incrementAndGet();
    }

    public void endRequest() {
        activeRequests.decrementAndGet();
    }

    public boolean isAvailable() {
        return stateMachine.current() == RuntimeStatus.ACTIVE &&
                instancePool.hasAvailableInstance();
    }

    /**
     * 宏观状态便捷方法
     */
    public RuntimeStatus currentStatus() {
        return stateMachine.current();
    }

    /**
     * 获取所有 READY 状态实例（用于路由选择）
     */
    public List<LingInstance> getReadyInstances() {
        return instancePool.getActiveInstances().stream()
                .filter(LingInstance::isReady)
                .collect(Collectors.toList());
    }
}
