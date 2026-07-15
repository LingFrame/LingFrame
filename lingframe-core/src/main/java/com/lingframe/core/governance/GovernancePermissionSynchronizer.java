package com.lingframe.core.governance;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionService;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
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

        // 先在内存构建完整权限映射，再通过 replacePermissions 原子替换。
        // 历史实现先 removeLing 再逐条 grant，两者之间存在权限真空窗口，
        // 期间该灵元的所有请求都会被拒绝。
        Map<String, AccessType> newPermissions = new HashMap<>();

        if (policy != null && policy.getCapabilities() != null && !policy.getCapabilities().isEmpty()) {
            for (GovernancePolicy.CapabilityRule rule : policy.getCapabilities()) {
                String capability = normalize(rule == null ? null : rule.getCapability());
                String accessTypeName = normalize(rule == null ? null : rule.getAccessType());

                if (capability == null || accessTypeName == null) {
                    log.warn("[Governance] Skip malformed capability rule for ling {}", normalizedLingId);
                    continue;
                }

                try {
                    AccessType accessType = AccessType.valueOf(accessTypeName.toUpperCase(Locale.ROOT));
                    newPermissions.put(capability, accessType);
                } catch (IllegalArgumentException ex) {
                    log.warn("[Governance] Skip invalid access type for ling {}: capability={}, accessType={}",
                            normalizedLingId, capability, accessTypeName);
                }
            }
        }

        // 原子替换：避免权限真空窗口
        permissionService.replacePermissions(normalizedLingId, newPermissions);

        if (newPermissions.isEmpty()) {
            log.info("[Governance] Cleared runtime permissions for ling {} from persisted policy", normalizedLingId);
            return 0;
        }

        // 返回实际生效的权限数（newPermissions.size()），而非规则计数。
        // 当存在重复 capability 时，newPermissions.put 会覆盖旧值，
        // 实际生效权限数 = newPermissions.size() <= 遍历的规则数。
        int effectiveCount = newPermissions.size();
        log.info("[Governance] Restored {} runtime permission(s) for ling {}",
                effectiveCount, normalizedLingId);
        return effectiveCount;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
