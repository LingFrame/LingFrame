package com.lingframe.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.governance.GovernanceAdminService;
import com.lingframe.dashboard.dto.InvocationGovernanceDTO;
import com.lingframe.dashboard.dto.ResourcePermissionDTO;
import com.lingframe.dashboard.storage.GovernanceStorage;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard 治理策略辅助类，集中处理 patch 合并与权限同步。
 * <p>
 * 内部委托 {@link GovernanceAdminService} 完成策略合并 / patch 管理 / 权限同步，
 * Dashboard 不再直接持有 {@code LocalGovernanceRegistry} 或调
 * {@code GovernancePermissionSynchronizer} 静态方法。
 * 本类只承载 Dashboard 特有的 DTO 镜像逻辑（{@link ResourcePermissionDTO} /
 * {@link InvocationGovernanceDTO}），不下沉 core。
 */
@Slf4j
public class DashboardGovernanceSupport {

    private final GovernanceAdminService governanceAdmin;
    private final PermissionService permissionService;
    // 复用 Spring 容器中的单例 ObjectMapper，避免每次序列化都创建新实例
    private final ObjectMapper objectMapper;

    // 持久化存储（可选，由 DashboardService.setGovernanceStorage 间接注入）
    private GovernanceStorage governanceStorage;

    public void setGovernanceStorage(GovernanceStorage governanceStorage) {
        this.governanceStorage = governanceStorage;
    }

    public DashboardGovernanceSupport(GovernanceAdminService governanceAdmin,
            PermissionService permissionService,
            ObjectMapper objectMapper) {
        this.governanceAdmin = governanceAdmin;
        this.permissionService = permissionService;
        this.objectMapper = objectMapper;
    }

    public GovernancePolicy getEffectivePolicy(String lingId) {
        return governanceAdmin.getEffectivePolicy(lingId);
    }

    public GovernancePolicy getPatchForUpdate(String lingId) {
        return governanceAdmin.getPatchForUpdate(lingId);
    }

    public void persistPolicyPatch(String lingId, GovernancePolicy patch) {
        governanceAdmin.persistPolicyPatch(lingId, patch);
    }

    public void updateGovernancePolicy(String lingId, GovernancePolicy policy) {
        GovernancePolicy mergedPatch = GovernancePolicy.merge(getPatchForUpdate(lingId), policy);
        persistPolicyPatch(lingId, mergedPatch);
        persistToStorage(lingId, mergedPatch);
    }

    public void updatePermissions(String lingId, ResourcePermissionDTO dto) {
        GovernancePolicy policy = getPatchForUpdate(lingId);
        Map<String, GovernancePolicy.CapabilityRule> ruleMap = new HashMap<>();

        if (policy.getCapabilities() != null) {
            for (GovernancePolicy.CapabilityRule rule : policy.getCapabilities()) {
                ruleMap.put(rule.getCapability(), rule);
            }
        }

        AccessType sqlAccess = determineAccessType(dto.isDbRead(), dto.isDbWrite());
        AccessType cacheAccess = determineAccessType(dto.isCacheRead(), dto.isCacheWrite());
        ruleMap.put(Capabilities.STORAGE_SQL, capabilityRule(Capabilities.STORAGE_SQL, sqlAccess));
        ruleMap.put(Capabilities.CACHE_LOCAL, capabilityRule(Capabilities.CACHE_LOCAL, cacheAccess));
        ruleMap.put(Capabilities.LING_ENABLE, capabilityRule(Capabilities.LING_ENABLE, AccessType.EXECUTE));

        if (dto.getIpcServices() != null) {
            List<String> toRemove = new ArrayList<>();
            for (String key : ruleMap.keySet()) {
                if (key.startsWith(Capabilities.IPC_PREFIX)) {
                    toRemove.add(key);
                }
            }
            toRemove.forEach(ruleMap::remove);
            for (String targetLingId : dto.getIpcServices()) {
                String ipcCapability = Capabilities.ipcCapability(targetLingId);
                ruleMap.put(ipcCapability, capabilityRule(ipcCapability, AccessType.EXECUTE));
            }
        }

        policy.setCapabilities(new ArrayList<>(ruleMap.values()));
        persistPolicyPatch(lingId, policy);
        persistToStorage(lingId, policy);
    }

    public InvocationGovernanceDTO updateInvocationGovernance(String lingId, InvocationGovernanceDTO dto) {
        GovernancePolicy patch = getPatchForUpdate(lingId);
        GovernancePolicy.InvocationPolicy invocation = patch.getInvocation();
        if (invocation == null) {
            invocation = new GovernancePolicy.InvocationPolicy();
        }

        invocation.setTimeoutMs(dto.getTimeoutMs());
        invocation.setRateLimitPerSecond(dto.getRateLimitPerSecond());
        invocation.setMaxConcurrentThreads(dto.getMaxConcurrentThreads());
        invocation.setRetryCount(dto.getRetryCount());
        invocation.setFallbackValue(dto.getFallbackValue());
        invocation.setCpuBudgetMsPerMinute(dto.getCpuBudgetMsPerMinute());
        invocation.setMemoryBudgetMb(dto.getMemoryBudgetMb());
        patch.setInvocation(invocation);

        persistPolicyPatch(lingId, patch);
        persistToStorage(lingId, patch);
        return getInvocationGovernance(lingId);
    }

    public InvocationGovernanceDTO getInvocationGovernance(String lingId) {
        GovernancePolicy effectivePolicy = getEffectivePolicy(lingId);
        GovernancePolicy.InvocationPolicy invocation =
                effectivePolicy == null ? null : effectivePolicy.getInvocation();
        return InvocationGovernanceDTO.builder()
                .timeoutMs(invocation == null ? null : invocation.getTimeoutMs())
                .rateLimitPerSecond(invocation == null ? null : invocation.getRateLimitPerSecond())
                .maxConcurrentThreads(invocation == null ? null : invocation.getMaxConcurrentThreads())
                .retryCount(invocation == null ? null : invocation.getRetryCount())
                .fallbackValue(invocation == null ? null : invocation.getFallbackValue())
                .cpuBudgetMsPerMinute(invocation == null ? null : invocation.getCpuBudgetMsPerMinute())
                .memoryBudgetMb(invocation == null ? null : invocation.getMemoryBudgetMb())
                .build();
    }

    private GovernancePolicy.CapabilityRule capabilityRule(String capability, AccessType accessType) {
        return GovernancePolicy.CapabilityRule.builder()
                .capability(capability)
                .accessType(accessType.name())
                .build();
    }

    private AccessType determineAccessType(boolean read, boolean write) {
        if (write) {
            return AccessType.WRITE;
        }
        if (read) {
            return AccessType.READ;
        }
        return AccessType.NONE;
    }

    /**
     * 将治理策略持久化到 SQLite（异步容错，不影响主流程）
     */
    private void persistToStorage(String lingId, GovernancePolicy policy) {
        if (governanceStorage == null) {
            return;
        }
        try {
            governanceStorage.saveInvocationConfig(lingId, objectMapper.writeValueAsString(policy));
        } catch (Exception e) {
            log.warn("Failed to persist governance strategy: {}", lingId, e);
        }
    }
}
