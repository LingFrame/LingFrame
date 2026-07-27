package com.lingframe.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.api.exception.LingNotFoundException;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.exception.LingInstallException;
import com.lingframe.core.fsm.RuntimeCoordinator;
import com.lingframe.core.fsm.RuntimeStatus;
import com.lingframe.core.fsm.TransitionRecord;
import com.lingframe.core.governance.GovernanceAdminService;
import com.lingframe.core.ling.LingLifecycleEngine;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.loader.LingManifestLoader;
import com.lingframe.core.routing.MigrationStateHolder;
import com.lingframe.dashboard.converter.LingInfoConverter;
import com.lingframe.dashboard.dto.InvocationGovernanceDTO;
import com.lingframe.dashboard.dto.LingInfoDTO;
import com.lingframe.dashboard.dto.LingUninstallResultDTO;
import com.lingframe.dashboard.dto.LingPackageDTO;
import com.lingframe.dashboard.dto.ResourcePermissionDTO;
import com.lingframe.dashboard.dto.TransitionHistoryDTO;
import com.lingframe.dashboard.dto.TrafficStatsDTO;
import com.lingframe.dashboard.storage.GovernanceStorage;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
public class DashboardService {

    @Data
    public static class LifecycleEvent {
        private final String id = UUID.randomUUID().toString();
        private final String lingId;
        private final String version;
        private final String type;
        private final String title;
        private final String description;
        private final long timestamp = System.currentTimeMillis();
    }

    private final LingRepository lingRepository;
    private final MigrationStateHolder migrationStateHolder;
    private final LingInfoConverter converter;
    private final PermissionService permissionService;
    private final DashboardGovernanceSupport governanceSupport;
    private final DashboardLifecycleEventStore lifecycleEventStore;
    private final DashboardStatusCoordinator statusCoordinator;
    private final DashboardLingOperations lingOperations;
    private final DashboardUninstallResultMapper uninstallResultMapper;
    // 复用 Spring 容器中的单例 ObjectMapper，避免每次灰度配置序列化都创建新实例
    private final ObjectMapper objectMapper;

    // 持久化存储（可选，SQLite 启用时注入）
    private GovernanceStorage governanceStorage;

    /**
     * 条件注入 GovernanceStorage，同时传递给 governanceSupport
     */
    public void setGovernanceStorage(GovernanceStorage governanceStorage) {
        this.governanceStorage = governanceStorage;
        if (this.governanceSupport != null) {
            this.governanceSupport.setGovernanceStorage(governanceStorage);
        }
    }

    public DashboardService(LingFrameConfig lingFrameConfig,
            LingLifecycleEngine lifecycleEngine,
            LingRepository lingRepository,
            GovernanceAdminService governanceAdmin,
            LingInfoConverter converter,
            PermissionService permissionService,
            RuntimeCoordinator runtimeCoordinator,
            MigrationStateHolder migrationStateHolder,
            ObjectMapper objectMapper) {
        this(
                lingRepository,
                converter,
                permissionService,
                new DashboardGovernanceSupport(governanceAdmin, permissionService, objectMapper),
                new DashboardLifecycleEventStore(),
                new DashboardLingSourceResolver(lingFrameConfig),
                lifecycleEngine,
                runtimeCoordinator,
                new DashboardUninstallResultMapper(),
                objectMapper,
                migrationStateHolder);
    }

    DashboardService(LingRepository lingRepository,
            LingInfoConverter converter,
            PermissionService permissionService,
            DashboardGovernanceSupport governanceSupport,
            DashboardLifecycleEventStore lifecycleEventStore,
            DashboardLingSourceResolver lingSourceResolver,
            LingLifecycleEngine lifecycleEngine,
            RuntimeCoordinator runtimeCoordinator,
            DashboardUninstallResultMapper uninstallResultMapper,
            ObjectMapper objectMapper,
            MigrationStateHolder migrationStateHolder) {
        this.lingRepository = lingRepository;
        this.converter = converter;
        this.permissionService = permissionService;
        this.governanceSupport = governanceSupport;
        this.lifecycleEventStore = lifecycleEventStore;
        this.uninstallResultMapper = uninstallResultMapper;
        this.objectMapper = objectMapper;
        this.migrationStateHolder = migrationStateHolder;
        this.statusCoordinator = new DashboardStatusCoordinator(
                lifecycleEngine,
                permissionService,
                runtimeCoordinator,
                governanceSupport,
                lifecycleEventStore);
        this.lingOperations = new DashboardLingOperations(
                lifecycleEngine,
                lingRepository,
                migrationStateHolder,
                lifecycleEventStore,
                lingSourceResolver);
    }

    public List<LingInfoDTO> getAllLingInfos() {
        return lingRepository.getAllRuntimes().stream()
                .filter(Objects::nonNull)
                .map(runtime -> converter.toDTO(
                        runtime,
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
                permissionService,
                governanceSupport.getEffectivePolicy(lingId));
    }

    public LingInfoDTO installLing(File file) {
        return getLingInfo(lingOperations.installLing(file));
    }

    public LingUninstallResultDTO uninstallLing(String lingId) {
        return uninstallLing(lingId, false);
    }

    public LingUninstallResultDTO uninstallLing(String lingId, boolean deleteFile) {
        LingUninstallResultDTO result = uninstallResultMapper.toDto(lingOperations.uninstallLing(lingId));
        if (deleteFile) {
            deleteHomePackageFile(lingId, null);
        }
        return result;
    }

    public LingUninstallResultDTO uninstallLing(String lingId, String version) {
        return uninstallLing(lingId, version, false);
    }

    public LingUninstallResultDTO uninstallLing(String lingId, String version, boolean deleteFile) {
        LingUninstallResultDTO result = uninstallResultMapper.toDto(lingOperations.uninstallLing(lingId, version));
        if (deleteFile) {
            deleteHomePackageFile(lingId, version);
        }
        return result;
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

    public void resetTrafficStats(String lingId) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingNotFoundException(lingId);
        }
        // 流量统计已下沉到 ProviderMetricsCollector / LingHealthMetrics，LingRuntime 不再背
        // 此方法保留为 Dashboard 兼容入口，实际清理由治理存储层处理
        log.debug("[Dashboard] resetTrafficStats requested for {} (handled by metrics collector)", lingId);
    }

    public TrafficStatsDTO getTrafficStats(String lingId) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null) {
            throw new LingNotFoundException(lingId);
        }
        return converter.toTrafficStats(runtime);
    }

    public List<LifecycleEvent> getLifecycleEvents(String lingId) {
        return lifecycleEventStore.getEvents(lingId);
    }

    public void updatePermissions(String lingId, ResourcePermissionDTO dto) {
        log.info("Updating permissions for ling {}: dbRead={}, dbWrite={}, cacheRead={}, cacheWrite={}",
                lingId, dto.isDbRead(), dto.isDbWrite(), dto.isCacheRead(), dto.isCacheWrite());
        governanceSupport.updatePermissions(lingId, dto);
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

        return statusCoordinator.getTransitionHistory(lingId).stream()
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

    /**
     * 扫描磁盘 ling-home 下的所有静态灵元 Jar 包及权限契约。
     */
    public List<LingPackageDTO> scanPackages() {
        List<File> files = lingOperations.getLingSourceResolver().listHomeFiles();
        if (files == null || files.isEmpty()) {
            return Collections.emptyList();
        }

        List<LingPackageDTO> packageList = new ArrayList<>();
        for (File file : files) {
            try {
                LingDefinition definition = LingManifestLoader.parseDefinition(file);
                if (definition == null) {
                    continue;
                }
                
                String lingId = definition.getId();
                String version = definition.getVersion();
                
                boolean isInstalled = false;
                LingRuntime runtime = lingRepository.getRuntime(lingId);
                if (runtime != null) {
                    isInstalled = runtime.getInstancePool().getInstance(version) != null;
                }

                List<String> declaredPerms = new ArrayList<>();
                if (definition.getGovernance() != null) {
                    if (definition.getGovernance().getCapabilities() != null) {
                        for (GovernancePolicy.CapabilityRule rule : definition.getGovernance().getCapabilities()) {
                            if (rule.getCapability() != null) {
                                declaredPerms.add(rule.getCapability() + " (" + (rule.getAccessType() != null ? rule.getAccessType() : "EXECUTE") + ")");
                            }
                        }
                    }
                    if (definition.getGovernance().getPermissions() != null) {
                        for (GovernancePolicy.PermissionRule rule : definition.getGovernance().getPermissions()) {
                            if (rule.getMethodPattern() != null) {
                                declaredPerms.add("Method: " + rule.getMethodPattern() + " [" + (rule.getPermissionId() != null ? rule.getPermissionId() : "ALLOW") + "]");
                            }
                        }
                    }
                }

                packageList.add(LingPackageDTO.builder()
                        .lingId(lingId)
                        .version(version)
                        .fileName(file.getName())
                        .fileSize(file.length())
                        .mainClass(definition.getMainClass())
                        .isInstalled(isInstalled)
                        .permissions(declaredPerms)
                        .build());
            } catch (Exception e) {
                log.warn("Failed to parse disk package, skipped: {}", file.getName(), e);
            }
        }
        return packageList;
    }

    /**
     * 将磁盘上已存在的物理包重新部署冷启动
     */
    public LingInfoDTO deployPackage(String lingId, String version) {
        File file = lingOperations.getLingSourceResolver().resolveSourceFile(lingId, version);
        if (file == null || !file.exists()) {
            throw new LingInstallException(lingId, "物理包文件不存在: " + lingId + ":" + version, null);
        }
        String id = lingOperations.installLing(file);
        return getLingInfo(id);
    }

    private void deleteHomePackageFile(String lingId, String version) {
        try {
            File file = lingOperations.getLingSourceResolver().resolveSourceFile(lingId, version);
            if (file != null && file.exists()) {
                log.info("Deleted physical package file: {}", file.getAbsolutePath());
                if (!file.delete()) {
                    log.warn("Cannot delete file physically, will try to delete on JVM exit: {}", file.getAbsolutePath());
                    file.deleteOnExit();
                }
            }
        } catch (Exception e) {
            log.warn("Exception deleting physical file: {}:{}", lingId, version, e);
        }
    }
}
