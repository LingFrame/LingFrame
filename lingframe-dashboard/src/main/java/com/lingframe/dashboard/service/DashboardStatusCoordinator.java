package com.lingframe.dashboard.service;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.fsm.StateMachine;
import com.lingframe.core.fsm.TransitionResult;
import com.lingframe.core.ling.LingLifecycleEngine;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;

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
     */
    private void activateLing(String lingId, String version, RuntimeStatus currentStatus) {
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

        GovernancePolicy effectivePolicy = governanceSupport.getEffectivePolicy(lingId);
        if (effectivePolicy == null
                || effectivePolicy.getCapabilities() == null
                || effectivePolicy.getCapabilities().isEmpty()) {
            GovernancePolicy patch = governanceSupport.getPatchForUpdate(lingId);
            patch.setCapabilities(Arrays.asList(
                    capabilityRule(Capabilities.STORAGE_SQL, AccessType.WRITE),
                    capabilityRule(Capabilities.CACHE_LOCAL, AccessType.WRITE),
                    capabilityRule(Capabilities.Ling_ENABLE, AccessType.EXECUTE)));
            governanceSupport.persistPolicyPatch(lingId, patch);
        }
    }

    private void deactivateLing(String lingId, String version, RuntimeStatus currentStatus) {
        TransitionResult<RuntimeStatus> inactiveResult = runtimeCoordinator.transition(lingId, RuntimeStatus.INACTIVE);
        if (!inactiveResult.isSuccess()) {
            String errorMessage = String.format("Cannot transition %s to INACTIVE from %s: %s",
                    lingId, currentStatus, inactiveResult.code());
            log.warn("[Dashboard] {}", errorMessage);
            throw new IllegalStateException(errorMessage);
        }

        log.info("[Dashboard] State transitioned to INACTIVE for ling: {}", lingId);
        lifecycleEventStore.addEvent(lingId, version, "STOPPING", "灵元停用", "灵元 " + lingId + " 已停用，不再接受新请求");
        permissionService.revoke(lingId, Capabilities.Ling_ENABLE);
        log.info("[Dashboard] Revoked Ling_ENABLE permission from {}, ling deactivated", lingId);
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

    private GovernancePolicy.CapabilityRule capabilityRule(String capability, AccessType accessType) {
        return GovernancePolicy.CapabilityRule.builder()
                .capability(capability)
                .accessType(accessType.name())
                .build();
    }

    /**
     * 获取指定灵元的运行时状态
     */
    RuntimeStatus getRuntimeStatus(String lingId) {
        return runtimeCoordinator.getStatus(lingId);
    }

    /**
     * 获取指定灵元的运行时状态机（用于查询转换历史）
     */
    StateMachine<RuntimeStatus> getRuntimeMachine(String lingId) {
        return runtimeCoordinator.getMachine(lingId);
    }
}
