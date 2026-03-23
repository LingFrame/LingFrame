package com.lingframe.core.governance;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 将已持久化的治理能力规则同步回运行时权限表。
 */
@Slf4j
public final class GovernancePermissionSynchronizer {

    private GovernancePermissionSynchronizer() {
    }

    public static int syncAll(LocalGovernanceRegistry registry, PermissionService permissionService) {
        Objects.requireNonNull(registry, "registry");
        return syncAll(registry.getAllPatches(), permissionService);
    }

    static int syncAll(Map<String, GovernancePolicy> patches, PermissionService permissionService) {
        Objects.requireNonNull(permissionService, "permissionService");

        if (patches == null || patches.isEmpty()) {
            return 0;
        }

        int syncedLingCount = 0;
        for (Map.Entry<String, GovernancePolicy> entry : patches.entrySet()) {
            String lingId = normalize(entry.getKey());
            if (lingId == null) {
                log.warn("[Governance] Skip syncing patch with blank lingId");
                continue;
            }
            syncPolicy(lingId, entry.getValue(), permissionService);
            syncedLingCount++;
        }
        return syncedLingCount;
    }

    public static int syncPolicy(String lingId, GovernancePolicy policy, PermissionService permissionService) {
        Objects.requireNonNull(permissionService, "permissionService");

        String normalizedLingId = normalize(lingId);
        if (normalizedLingId == null) {
            log.warn("[Governance] Skip syncing policy with blank lingId");
            return 0;
        }

        permissionService.removeLing(normalizedLingId);

        if (policy == null || policy.getCapabilities() == null || policy.getCapabilities().isEmpty()) {
            log.info("[Governance] Cleared runtime permissions for ling {} from persisted policy", normalizedLingId);
            return 0;
        }

        int syncedPermissionCount = 0;
        for (GovernancePolicy.CapabilityRule rule : policy.getCapabilities()) {
            String capability = normalize(rule == null ? null : rule.getCapability());
            String accessTypeName = normalize(rule == null ? null : rule.getAccessType());

            if (capability == null || accessTypeName == null) {
                log.warn("[Governance] Skip malformed capability rule for ling {}", normalizedLingId);
                continue;
            }

            try {
                AccessType accessType = AccessType.valueOf(accessTypeName.toUpperCase(Locale.ROOT));
                permissionService.grant(normalizedLingId, capability, accessType);
                syncedPermissionCount++;
            } catch (IllegalArgumentException ex) {
                log.warn("[Governance] Skip invalid access type for ling {}: capability={}, accessType={}",
                        normalizedLingId, capability, accessTypeName);
            }
        }

        log.info("[Governance] Restored {} runtime permission(s) for ling {}",
                syncedPermissionCount, normalizedLingId);
        return syncedPermissionCount;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
