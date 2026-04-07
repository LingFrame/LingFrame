package com.lingframe.dashboard.service;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.Capabilities;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.governance.GovernancePermissionSynchronizer;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.dashboard.dto.InvocationGovernanceDTO;
import com.lingframe.dashboard.dto.ResourcePermissionDTO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard 治理策略辅助类，集中处理 patch 合并与权限同步。
 */
public class DashboardGovernanceSupport {

    private final LingRepository lingRepository;
    private final LocalGovernanceRegistry governanceRegistry;
    private final PermissionService permissionService;

    public DashboardGovernanceSupport(LingRepository lingRepository,
            LocalGovernanceRegistry governanceRegistry,
            PermissionService permissionService) {
        this.lingRepository = lingRepository;
        this.governanceRegistry = governanceRegistry;
        this.permissionService = permissionService;
    }

    public GovernancePolicy getEffectivePolicy(String lingId) {
        GovernancePolicy staticPolicy = getStaticPolicy(lingId);
        GovernancePolicy patch = governanceRegistry.getPatch(lingId);
        if (staticPolicy == null && patch == null) {
            return null;
        }
        return GovernancePolicy.merge(staticPolicy, patch);
    }

    public GovernancePolicy getPatchForUpdate(String lingId) {
        GovernancePolicy patch = governanceRegistry.getPatch(lingId);
        return patch == null ? new GovernancePolicy() : patch.copy();
    }

    public void persistPolicyPatch(String lingId, GovernancePolicy patch) {
        governanceRegistry.updatePatch(lingId, patch);
        GovernancePermissionSynchronizer.syncPolicy(lingId, getEffectivePolicy(lingId), permissionService);
    }

    public void updateGovernancePolicy(String lingId, GovernancePolicy policy) {
        GovernancePolicy mergedPatch = GovernancePolicy.merge(getPatchForUpdate(lingId), policy);
        persistPolicyPatch(lingId, mergedPatch);
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
        ruleMap.put(Capabilities.Ling_ENABLE, capabilityRule(Capabilities.Ling_ENABLE, AccessType.EXECUTE));

        if (dto.getIpcServices() != null) {
            List<String> toRemove = new ArrayList<>();
            for (String key : ruleMap.keySet()) {
                if (key.startsWith("ipc:")) {
                    toRemove.add(key);
                }
            }
            toRemove.forEach(ruleMap::remove);
            for (String targetLingId : dto.getIpcServices()) {
                ruleMap.put("ipc:" + targetLingId, capabilityRule("ipc:" + targetLingId, AccessType.EXECUTE));
            }
        }

        policy.setCapabilities(new ArrayList<>(ruleMap.values()));
        persistPolicyPatch(lingId, policy);
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

    private GovernancePolicy getStaticPolicy(String lingId) {
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime != null && runtime.getInstancePool().getDefault() != null
                && runtime.getInstancePool().getDefault().getDefinition() != null) {
            GovernancePolicy governance = runtime.getInstancePool().getDefault().getDefinition().getGovernance();
            return governance == null ? null : governance.copy();
        }
        return null;
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
}
