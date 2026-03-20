package com.lingframe.core.security;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionAuditRecord;
import com.lingframe.api.security.PermissionAuditResult;
import com.lingframe.api.security.PermissionInfo;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.audit.AuditManager;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认的运行时权限服务实现。
 */
@Slf4j
public class DefaultPermissionService implements PermissionService {

    private static final String GLOBAL_WHITELIST_PREFIX = "com.lingframe.api.";
    private static final String LING_CORE_ID = "lingcore-app";

    private final EventBus eventBus;
    private final Map<String, Map<String, AccessType>> permissions = new ConcurrentHashMap<>();

    public DefaultPermissionService(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public boolean isAllowed(String lingId, String capability, AccessType accessType) {
        log.debug("[Auth] Checking permission: lingId={}, capability={}, accessType={}", lingId, capability, accessType);

        if (capability == null) {
            log.warn("[Auth] Capability is null, rejecting by default");
            return false;
        }

        if (LING_CORE_ID.equals(lingId) && !LingFrameConfig.current().isHostCheckPermissions()) {
            log.debug("[Auth] LINGCORE application bypassed");
            return true;
        }

        if (lingId == null || capability.startsWith(GLOBAL_WHITELIST_PREFIX)) {
            log.debug("[Auth] Whitelist bypassed");
            return true;
        }

        boolean allowed = checkInternal(lingId, capability, accessType);
        log.debug("[Auth] Permission table check result: {}", allowed);

        if (!allowed && LingFrameConfig.current().isDevMode()) {
            log.warn("==========================================================================");
            log.warn("[DEV WARNING] ling [{}] unauthorized access [{}] ({}). Please declare in ling.yml: {}",
                    lingId, capability, accessType, capability);
            log.warn("==========================================================================");
            return true;
        }

        return allowed;
    }

    private boolean checkInternal(String lingId, String capability, AccessType accessType) {
        Map<String, AccessType> lingPerms = permissions.get(lingId);
        log.debug("[Auth-Internal] lingId={}, grantedCapabilityCount={}",
                lingId,
                lingPerms == null ? 0 : lingPerms.size());

        if (lingPerms == null) {
            log.debug("[Auth-Internal] ling has no permissions -> false");
            return false;
        }

        AccessType granted = lingPerms.get(capability);
        log.debug("[Auth-Internal] Query capability={}, granted={}", capability, granted);

        if (granted == null) {
            log.debug("[Auth-Internal] Capability not granted -> false");
            return false;
        }

        boolean result = granted.satisfies(accessType);
        log.debug("[Auth-Internal] granted({}).satisfies(required({})) = {}", granted, accessType, result);
        return result;
    }

    @Override
    public void grant(String lingId, String capability, AccessType accessType) {
        log.info("[PermissionService] Granting permission: lingId={}, capability={}, accessType={}",
                lingId, capability, accessType);
        permissions.computeIfAbsent(lingId, k -> new ConcurrentHashMap<>()).put(capability, accessType);
        log.debug("[PermissionService] Permission saved for lingId={}, capability={}", lingId, capability);
    }

    @Override
    public void revoke(String lingId, String capability) {
        log.info("[PermissionService] Revoking permission: lingId={}, capability={}", lingId, capability);
        permissions.computeIfAbsent(lingId, k -> new ConcurrentHashMap<>()).put(capability, AccessType.NONE);
        log.debug("[PermissionService] Permission set to NONE for lingId={}, capability={}", lingId, capability);
    }

    @Override
    public PermissionInfo getPermission(String lingId, String capability) {
        AccessType accessType = permissions.getOrDefault(lingId, Collections.emptyMap()).get(capability);
        if (accessType == null) {
            return null;
        }
        return PermissionInfo.permanent(lingId, capability, accessType, "runtime-grant");
    }

    @Override
    public void audit(String lingId, String capability, String operation, boolean allowed) {
        audit(PermissionAuditRecord.builder()
                .callerLingId(lingId)
                .capability(capability)
                .action(operation)
                .resource(capability)
                .result(allowed ? PermissionAuditResult.ALLOWED : PermissionAuditResult.DENIED)
                .costNanos(0L)
                .build());
    }

    @Override
    public void audit(PermissionAuditRecord record) {
        if (record == null || record.getResult() == null) {
            return;
        }

        String traceId = LingCallContext.startTrace();
        String callerLingId = record.getCallerLingId();
        String principal = normalize(record.getPrincipal());
        String capability = normalize(record.getCapability());
        String action = truncate(record.getAction(), 80);
        String resource = truncate(record.getResource(), 120);
        String failureReason = truncate(record.getFailureReason(), 160);

        if (record.getResult() == PermissionAuditResult.DENIED) {
            log.warn("[Security] Access denied - caller={}, capability={}, action={}, resource={}",
                    callerLingId, capability, action, resource);
        } else if (record.getResult() == PermissionAuditResult.FAILED) {
            log.warn("[Security] Allowed invocation failed - caller={}, capability={}, action={}, reason={}",
                    callerLingId, capability, action, failureReason);
        }

        AuditManager.asyncRecord(
                traceId,
                callerLingId,
                principal,
                record.getResult(),
                capability,
                action,
                resource,
                failureReason,
                record.getCostNanos());

        if (eventBus != null) {
            eventBus.publish(new MonitoringEvents.AuditLogEvent(
                    traceId,
                    callerLingId,
                    principal,
                    action,
                    resource,
                    capability,
                    record.getResult(),
                    failureReason,
                    record.getCostNanos()));
        }
    }

    @Override
    public void removeLing(String lingId) {
        if (permissions.remove(lingId) != null) {
            log.debug("Removed permissions for ling: {}", lingId);
        }
    }

    @Override
    public boolean isLingCoreGovernanceEnabled() {
        return LingFrameConfig.current().isLingCoreGovernanceEnabled();
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
