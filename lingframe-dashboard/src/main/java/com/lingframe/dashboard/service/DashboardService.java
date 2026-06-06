package com.lingframe.dashboard.service;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.api.exception.LingNotFoundException;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.fsm.StateMachine;
import com.lingframe.core.fsm.TransitionRecord;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.router.CanaryRouter;
import com.lingframe.dashboard.converter.LingInfoConverter;
import com.lingframe.dashboard.dto.InvocationGovernanceDTO;
import com.lingframe.dashboard.dto.LingInfoDTO;
import com.lingframe.dashboard.dto.LingUninstallResultDTO;
import com.lingframe.dashboard.dto.ResourcePermissionDTO;
import com.lingframe.dashboard.dto.TransitionHistoryDTO;
import com.lingframe.dashboard.dto.TrafficStatsDTO;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
public class DashboardService {

    @Data
    public static class LifecycleEvent {
        private final String id = java.util.UUID.randomUUID().toString();
        private final String lingId;
        private final String version;
        private final String type;
        private final String title;
        private final String description;
        private final long timestamp = System.currentTimeMillis();
    }

    private final LingRepository lingRepository;
    private final CanaryRouter canaryRouter;
    private final LingInfoConverter converter;
    private final PermissionService permissionService;
    private final DashboardGovernanceSupport governanceSupport;
    private final DashboardLifecycleEventStore lifecycleEventStore;
    private final DashboardStatusCoordinator statusCoordinator;
    private final DashboardLingOperations lingOperations;
    private final DashboardUninstallResultMapper uninstallResultMapper;

    public DashboardService(LingFrameConfig lingFrameConfig,
            LingLifecycleEngine lifecycleEngine,
            LingRepository lingRepository,
            com.lingframe.core.governance.LocalGovernanceRegistry governanceRegistry,
            CanaryRouter canaryRouter,
            LingInfoConverter converter,
            PermissionService permissionService,
            RuntimeCoordinator runtimeCoordinator) {
        this(
                lingRepository,
                canaryRouter,
                converter,
                permissionService,
                new DashboardGovernanceSupport(lingRepository, governanceRegistry, permissionService),
                new DashboardLifecycleEventStore(),
                new DashboardLingSourceResolver(lingFrameConfig),
                lifecycleEngine,
                runtimeCoordinator,
                new DashboardUninstallResultMapper());
    }

    DashboardService(LingRepository lingRepository,
            CanaryRouter canaryRouter,
            LingInfoConverter converter,
            PermissionService permissionService,
            DashboardGovernanceSupport governanceSupport,
            DashboardLifecycleEventStore lifecycleEventStore,
            DashboardLingSourceResolver lingSourceResolver,
            LingLifecycleEngine lifecycleEngine,
            RuntimeCoordinator runtimeCoordinator,
            DashboardUninstallResultMapper uninstallResultMapper) {
        this.lingRepository = lingRepository;
        this.canaryRouter = canaryRouter;
        this.converter = converter;
        this.permissionService = permissionService;
        this.governanceSupport = governanceSupport;
        this.lifecycleEventStore = lifecycleEventStore;
        this.uninstallResultMapper = uninstallResultMapper;
        this.statusCoordinator = new DashboardStatusCoordinator(
                lifecycleEngine,
                permissionService,
                runtimeCoordinator,
                governanceSupport,
                lifecycleEventStore);
        this.lingOperations = new DashboardLingOperations(
                lifecycleEngine,
                lingRepository,
                canaryRouter,
                lifecycleEventStore,
                lingSourceResolver);
    }

    public List<LingInfoDTO> getAllLingInfos() {
        return lingRepository.getAllRuntimes().stream()
                .filter(Objects::nonNull)
                .map(runtime -> converter.toDTO(
                        runtime,
                        canaryRouter,
                        permissionService,
                        governanceSupport.getEffectivePolicy(runtime.getLingId())))
                .collect(Collectors.toList());
    }

    public LingInfoDTO getLingInfo(String lingId) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            return null;
        }
        return converter.toDTO(
                runtime,
                canaryRouter,
                permissionService,
                governanceSupport.getEffectivePolicy(lingId));
    }

    public LingInfoDTO installLing(File file) {
        return getLingInfo(lingOperations.installLing(file));
    }

    public LingUninstallResultDTO uninstallLing(String lingId) {
        return uninstallResultMapper.toDto(lingOperations.uninstallLing(lingId));
    }

    public LingUninstallResultDTO uninstallLing(String lingId, String version) {
        return uninstallResultMapper.toDto(lingOperations.uninstallLing(lingId, version));
    }

    public LingInfoDTO reloadLing(String lingId, String version) {
        return getLingInfo(lingOperations.reloadLing(lingId, version));
    }

    public LingInfoDTO updateStatus(String lingId, RuntimeStatus newStatus, String version) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingNotFoundException(lingId);
        }

        RuntimeStatus currentStatus = runtime.currentStatus();
        log.info("[Dashboard] Requesting status transition for ling {}: {} -> {}", lingId, currentStatus, newStatus);

        try {
            statusCoordinator.updateStatus(lingId, currentStatus, newStatus, version);
        } catch (IllegalArgumentException e) {
            throw new InvalidArgumentException("status", e.getMessage());
        }

        return getLingInfo(lingId);
    }

    public void setCanaryConfig(String lingId, int percent, String canaryVersion) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingNotFoundException(lingId);
        }
        canaryRouter.setCanaryConfig(lingId, percent, canaryVersion);
    }

    public TrafficStatsDTO getTrafficStats(String lingId) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingNotFoundException(lingId);
        }
        return converter.toTrafficStats(runtime);
    }

    public void resetTrafficStats(String lingId) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingNotFoundException(lingId);
        }
        runtime.resetTrafficStats();
    }

    public List<LifecycleEvent> getLifecycleEvents(String lingId) {
        return lifecycleEventStore.getEvents(lingId);
    }

    public void updatePermissions(String lingId, ResourcePermissionDTO dto) {
        log.info("========== Starting Permission Update ==========");
        log.info("Ling ID: {}", lingId);
        log.info("Received permissions: dbRead={}, dbWrite={}, cacheRead={}, cacheWrite={}",
                dto.isDbRead(), dto.isDbWrite(), dto.isCacheRead(), dto.isCacheWrite());
        governanceSupport.updatePermissions(lingId, dto);
        log.info("Permission update completed and persisted");
        log.info("========================================");
    }

    public void updateGovernancePolicy(String lingId, GovernancePolicy policy) {
        governanceSupport.updateGovernancePolicy(lingId, policy);
    }

    public InvocationGovernanceDTO updateInvocationGovernance(String lingId, InvocationGovernanceDTO dto) {
        return governanceSupport.updateInvocationGovernance(lingId, dto);
    }

    public InvocationGovernanceDTO getInvocationGovernance(String lingId) {
        return governanceSupport.getInvocationGovernance(lingId);
    }

    /**
     * 获取指定灵元的运行时状态机转换历史。
     * <p>
     * 从 {@link RuntimeCoordinator} 持有的状态机中读取环形缓冲区快照，
     * 转换为 DTO 供 Dashboard 展示状态转换时间线。
     *
     * @param lingId 灵元标识
     * @return 转换历史列表（从旧到新），灵元不存在时返回空列表
     */
    public List<TransitionHistoryDTO> getTransitionHistory(String lingId) {
        RuntimeStatus status = statusCoordinator.getRuntimeStatus(lingId);
        if (status == null) {
            return Collections.emptyList();
        }

        StateMachine<RuntimeStatus> fsm = statusCoordinator.getRuntimeMachine(lingId);
        if (fsm == null) {
            return Collections.emptyList();
        }

        return fsm.history().stream()
                .map(this::toTransitionHistoryDTO)
                .collect(Collectors.toList());
    }

    private TransitionHistoryDTO toTransitionHistoryDTO(TransitionRecord<RuntimeStatus> record) {
        return TransitionHistoryDTO.builder()
                .contextId(record.contextId())
                .from(record.from().name())
                .to(record.to().name())
                .timestamp(record.timestamp())
                .build();
    }
}
