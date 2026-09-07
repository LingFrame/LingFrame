package com.lingframe.core.ling;

import com.lingframe.core.event.EventBus;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * 虚拟灵元（Virtual Governance Ling）领域管理服务。
 * <p>
 * 专为 Java Agent 治理切点、API 流量网关、Service Mesh 等纯治理场景设计。
 * 虚拟灵元具备完备的微内核路由目标（{@link RoutableTarget}）身份与治理配置能力，
 * 但不持有物理实例池（{@link InstancePool}），免除物理 ClassLoader 与 Bean 容器开销。
 * <p>
 * 本管理器作为灵核一等公民服务，闭环封装了虚拟灵元的装配、双层状态机联动（register → ACTIVE）
 * 以及优雅下线全生命周期，对外彻底隐藏底层状态机时序细节。
 */
@Slf4j
public class VirtualLingManager {

    private final LingRepository lingRepository;
    private final RuntimeCoordinator runtimeCoordinator;
    private final EventBus eventBus;

    public VirtualLingManager(LingRepository lingRepository,
                              RuntimeCoordinator runtimeCoordinator,
                              EventBus eventBus) {
        this.lingRepository = Objects.requireNonNull(lingRepository, "LingRepository is required");
        this.runtimeCoordinator = Objects.requireNonNull(runtimeCoordinator, "RuntimeCoordinator is required");
        this.eventBus = eventBus;
    }

    /**
     * 注册并激活虚拟灵元。
     * <p>
     * 原子完成状态机初始化、运行时聚合根组装、仓储登记并推进至 {@link RuntimeStatus#ACTIVE} 状态。
     * 若已存在该虚拟灵元，则幂等更新其治理配置。
     *
     * @param lingId 虚拟灵元唯一标识
     * @param config 运行时治理配置，若为 null 则采用默认配置
     * @return 激活就绪的虚拟灵元运行时聚合根
     */
    public synchronized LingRuntime register(String lingId, LingRuntimeConfig config) {
        if (lingId == null || lingId.trim().isEmpty()) {
            throw new IllegalArgumentException("lingId cannot be null or empty");
        }
        final LingRuntimeConfig effectiveConfig = config != null ? config : LingRuntimeConfig.defaults();

        LingRuntime existing = lingRepository.getRuntime(lingId);
        if (existing != null) {
            if (existing.isVirtual()) {
                existing.updateConfig(effectiveConfig);
                if (existing.currentStatus() != RuntimeStatus.ACTIVE) {
                    runtimeCoordinator.transition(lingId, RuntimeStatus.ACTIVE);
                }
                log.info("[VirtualLing] Updated existing virtual ling [{}] configuration", lingId);
                return existing;
            }
            throw new IllegalStateException("Cannot register virtual ling: physical ling [" + lingId + "] already exists");
        }

        // 1. 初始化宏观运行时状态机（初始状态为 INACTIVE）
        runtimeCoordinator.register(lingId);

        // 2. 组装无物理实例池的虚拟灵元聚合根
        LingRuntime runtime = new LingRuntime(lingId, effectiveConfig, eventBus, runtimeCoordinator);

        // 3. 注册至微内核仓储
        lingRepository.register(runtime);

        // 4. 推进至 ACTIVE 状态，允许微内核流水线放行调用
        runtimeCoordinator.transition(lingId, RuntimeStatus.ACTIVE);

        log.info("[VirtualLing] Registered virtual ling [{}] with ACTIVE status, rateLimit={}/s, circuitBreakerFailureRate={}%",
                lingId,
                effectiveConfig.getRateLimitPerSecond(),
                effectiveConfig.getCircuitBreakerFailureRateThreshold());
        return runtime;
    }

    /**
     * 使用默认治理配置注册并激活虚拟灵元。
     *
     * @param lingId 虚拟灵元唯一标识
     * @return 激活就绪的虚拟灵元运行时聚合根
     */
    public LingRuntime register(String lingId) {
        return register(lingId, null);
    }

    /**
     * 优雅注销虚拟灵元。
     * <p>
     * 推进状态机至 {@link RuntimeStatus#STOPPING}，并从仓储与协调器中彻底移除。
     *
     * @param lingId 虚拟灵元唯一标识
     */
    public synchronized void unregister(String lingId) {
        if (lingId == null || lingId.trim().isEmpty()) {
            return;
        }
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            return;
        }

        try {
            RuntimeStatus current = runtimeCoordinator.getStatus(lingId);
            if (current == RuntimeStatus.ACTIVE || current == RuntimeStatus.DEGRADED) {
                runtimeCoordinator.transition(lingId, RuntimeStatus.STOPPING);
            }
        } catch (Exception e) {
            log.warn("[VirtualLing] Error transitioning virtual ling [{}] to STOPPING: {}", lingId, e.getMessage());
        } finally {
            lingRepository.unregister(lingId);
            runtimeCoordinator.unregister(lingId);
            log.info("[VirtualLing] Unregistered virtual ling [{}]", lingId);
        }
    }

    /**
     * 查询指定虚拟灵元运行时。
     *
     * @param lingId 灵元唯一标识
     * @return 虚拟灵元运行时聚合根，不存在或非虚拟灵元时返回 null
     */
    public LingRuntime getRuntime(String lingId) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        return (runtime != null && runtime.isVirtual()) ? runtime : null;
    }

    /**
     * 判断指定虚拟灵元是否存在。
     *
     * @param lingId 灵元唯一标识
     * @return 存在且为虚拟灵元返回 true，否则返回 false
     */
    public boolean hasRuntime(String lingId) {
        return getRuntime(lingId) != null;
    }
}