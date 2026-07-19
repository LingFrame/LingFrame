package com.lingframe.dashboard.service;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.fsm.TransitionRecord;
import com.lingframe.core.fsm.TransitionResult;
import com.lingframe.core.ling.LingLifecycleEngine;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Dashboard 状态切换编排器，集中处理状态迁移、副作用和时间线记录。
 */
@Slf4j
public class DashboardStatusCoordinator {

    private final LingLifecycleEngine lifecycleEngine;
    private final PermissionService permissionService;
    private final RuntimeCoordinator runtimeCoordinator;
    private final DashboardGovernanceSupport governanceSupport;
    private final DashboardLifecycleEventStore lifecycleEventStore;

    public DashboardStatusCoordinator(LingLifecycleEngine lifecycleEngine,
            PermissionService permissionService,
            RuntimeCoordinator runtimeCoordinator,
            DashboardGovernanceSupport governanceSupport,
            DashboardLifecycleEventStore lifecycleEventStore) {
        this.lifecycleEngine = lifecycleEngine;
        this.permissionService = permissionService;
        this.runtimeCoordinator = runtimeCoordinator;
        this.governanceSupport = governanceSupport;
        this.lifecycleEventStore = lifecycleEventStore;
    }

    public void updateStatus(String lingId, RuntimeStatus currentStatus, RuntimeStatus newStatus, String version) {
        switch (newStatus) {
            case ACTIVE:
                activateLing(lingId, version, currentStatus);
                break;
            case INACTIVE:
                deactivateLing(lingId, version, currentStatus);
                break;
            case RECOVERING:
                recoverLing(lingId, version);
                break;
            case REMOVED:
                lifecycleEngine.undeploy(lingId);
                break;
            default:
                throw new IllegalArgumentException("Unsupported status: " + newStatus);
        }
    }

    /**
     * 激活灵元。
     * <p>
     * 通过 {@link LingLifecycleEngine#recover} 编排恢复，而非直接 transition(ACTIVE)。
     * 这样确保实例层先就绪，再由 RuntimeCoordinator 聚合出 ACTIVE，
     * 避免"运行时说可服务但实例未就绪"的割裂。
     * <p>
     * 仅当灵元当前处于 INACTIVE（无可用实例）时才走 recover 路径；
     * 若已有可用实例，则直接 transition(ACTIVE) 即可。
     * <p>
     * 安全约束：激活前必须确认灵元已显式配置 capabilities。
     * 不再自动授予 WRITE 权限，避免无配置灵元上线后默认获得 SQL/Cache 写权限放大攻击面。
     * 未配置 capabilities 的灵元应在 Dashboard 显式配置治理策略后再激活。
     */
    private void activateLing(String lingId, String version, RuntimeStatus currentStatus) {
        // 激活前 fail-fast：未配置 capabilities 直接拒绝激活，提示用户先配置治理策略
        GovernancePolicy effectivePolicy = governanceSupport.getEffectivePolicy(lingId);
        if (effectivePolicy == null
                || effectivePolicy.getCapabilities() == null
                || effectivePolicy.getCapabilities().isEmpty()) {
            String errorMessage = String.format(
                    "Cannot activate %s: no capabilities configured. Please configure governance policy first.",
                    lingId);
            log.warn("[Dashboard] {}", errorMessage);
            throw new IllegalStateException(errorMessage);
        }

        if (currentStatus == RuntimeStatus.INACTIVE) {
            // INACTIVE 说明无可用实例，需要通过 recover 编排恢复
            try {
                lifecycleEngine.recover(lingId, version);
            } catch (Exception e) {
                String errorMessage = String.format("Cannot recover %s to ACTIVE: %s", lingId, e.getMessage());
                log.warn("[Dashboard] {}", errorMessage);
                throw new IllegalStateException(errorMessage, e);
            }
        } else {
            // 非 INACTIVE（如 DEGRADED/RECOVERING），直接 transition 即可
            TransitionResult<RuntimeStatus> activeResult = runtimeCoordinator.transition(lingId, RuntimeStatus.ACTIVE);
            if (!activeResult.isSuccess()) {
                String errorMessage = String.format("Cannot transition %s to ACTIVE from %s: %s",
                        lingId, currentStatus, activeResult.code());
                log.warn("[Dashboard] {}", errorMessage);
                throw new IllegalStateException(errorMessage);
            }
        }

        log.info("[Dashboard] Ling {} activated from {}", lingId, currentStatus);
        lifecycleEventStore.addEvent(lingId, version, "ACTIVE", "灵元激活", "灵元 " + lingId + " 已激活并开始处理请求");
    }

    /**
     * 控制面收回 {@link Capabilities#LING_ENABLE}，并尽力 transition 到 INACTIVE。
     * <p>
     * 流量切分只走二维路由/权重，不占用 RuntimeStatus。
     * INACTIVE 表示「无可用实例」的聚合事实；若实例仍 READY，reevaluate 可回到 ACTIVE。
     * 彻底下线请走卸载（REMOVED）。
     */
    private void deactivateLing(String lingId, String version, RuntimeStatus currentStatus) {
        permissionService.revoke(lingId, Capabilities.LING_ENABLE);
        log.info("[Dashboard] Revoked LING_ENABLE from {}", lingId);

        TransitionResult<RuntimeStatus> inactiveResult = runtimeCoordinator.transition(lingId, RuntimeStatus.INACTIVE);
        if (!inactiveResult.isSuccess()) {
            log.info("[Dashboard] transition INACTIVE for {} from {} result={} (permission already revoked)",
                    lingId, currentStatus, inactiveResult.code());
        }

        lifecycleEventStore.addEvent(lingId, version, "INACTIVE", "收回启用权限",
                "灵元 " + lingId + " 已收回 LING_ENABLE。切流请改路由权重；彻底下线请卸载。");
    }

    private void recoverLing(String lingId, String version) {
        lifecycleEngine.recover(lingId, version);
        log.info("[Dashboard] Recovery triggered for ling: {} version: {}", lingId, version);
        lifecycleEventStore.addEvent(
                lingId,
                version,
                "RECOVERING",
                "灵元恢复中",
                version == null
                        ? "灵元 " + lingId + " 已进入受控恢复流程"
                        : "灵元 " + lingId + " 版本 " + version + " 已进入受控恢复流程");
        lifecycleEventStore.addEvent(
                lingId,
                version,
                "ACTIVE",
                "灵元恢复完成",
                version == null
                        ? "灵元 " + lingId + " 已恢复到可服务状态"
                        : "灵元 " + lingId + " 版本 " + version + " 已恢复到可服务状态");
    }

    /**
     * 获取指定灵元的运行时状态
     */
    RuntimeStatus getRuntimeStatus(String lingId) {
        return runtimeCoordinator.getStatus(lingId);
    }

    /**
     * 获取指定灵元的状态转换历史。
     * <p>
     * 委托 {@link RuntimeCoordinator#getTransitionHistory(String)}，
     * 不再暴露 {@code StateMachine} 内部对象给上层。
     *
     * @param lingId 灵元 ID
     * @return 转换记录列表（时序快照）；灵元未注册或无记录时返回空列表
     */
    List<TransitionRecord<RuntimeStatus>> getTransitionHistory(String lingId) {
        return runtimeCoordinator.getTransitionHistory(lingId);
    }
}
