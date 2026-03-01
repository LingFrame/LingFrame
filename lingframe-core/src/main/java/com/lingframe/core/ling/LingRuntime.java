package com.lingframe.core.ling;

import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.fsm.StateMachine;
import lombok.Getter;
import lombok.ToString;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 单元运行时（V0.3.0 聚合根纯化版）
 * 仅作为状态、数据和多版本实例池的宿主，剥离了生命周期、调用和路由等重度行为。
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
    private volatile long statsWindowStart = System.currentTimeMillis();

    @Getter
    private final long installedAt = System.currentTimeMillis();

    public LingRuntime(String lingId, LingRuntimeConfig config) {
        this.lingId = lingId;
        this.config = config != null ? config : LingRuntimeConfig.defaults();
        this.instancePool = new InstancePool(lingId, this.config.getMaxHistorySnapshots());
        this.stateMachine = RuntimeStatus.newMachine();
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
        statsWindowStart = System.currentTimeMillis();
    }

    public boolean isAvailable() {
        return stateMachine.current() == RuntimeStatus.ACTIVE &&
                instancePool.hasAvailableInstance();
    }

    /** 宏观状态便利方法 */
    public RuntimeStatus currentStatus() {
        return stateMachine.current();
    }

    /** 获取所有 READY 状态实例（用于路由选择） */
    public List<LingInstance> getReadyInstances() {
        return instancePool.getActiveInstances().stream()
                .filter(LingInstance::isReady)
                .collect(Collectors.toList());
    }
}