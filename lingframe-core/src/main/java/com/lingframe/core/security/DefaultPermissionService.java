package com.lingframe.core.security;

import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionAuditRecord;
import com.lingframe.api.security.PermissionAuditResult;
import com.lingframe.api.security.PermissionInfo;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.pipeline.InvocationContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认的运行时权限服务实现。
 */
@Slf4j
public class DefaultPermissionService implements PermissionService {

    private static final String GLOBAL_WHITELIST_PREFIX = "com.lingframe.api.";
    private static final String LING_CORE_ID = "lingcore-app";

    private final EventBus eventBus;
    private final LingFrameConfig config;
    private final Map<String, Map<String, AccessType>> permissions = new ConcurrentHashMap<>();

    public DefaultPermissionService(EventBus eventBus, LingFrameConfig config) {
        this.eventBus = eventBus;
        this.config = Objects.requireNonNull(config,
                "LingFrameConfig is required for DefaultPermissionService");
    }

    @Override
    public boolean isAllowed(String lingId, String capability, AccessType accessType) {
        log.debug("[Auth] Checking permission: lingId={}, capability={}, accessType={}", lingId, capability, accessType);

        if (capability == null) {
            log.warn("[Auth] Capability is null, rejecting by default");
            return false;
        }

        // 灵元 API 契约包，明确放行（与调用方身份无关）
        if (capability.startsWith(GLOBAL_WHITELIST_PREFIX)) {
            log.debug("[Auth] Whitelist bypassed");
            return true;
        }

        // 显式灵核身份 + 未开启灵核权限检查
        if (LING_CORE_ID.equals(lingId) && !config.isLingCoreCheckPermissions()) {
            log.debug("[Auth] LINGCORE application bypassed");
            return true;
        }

        // lingId==null：未知调用方（无 LingCallContext），区分灵核治理开关
        // 避免无身份请求直接 fail-open 绕过权限边界，与 SQL proxy 行为对齐
        if (lingId == null) {
            if (config.isLingCoreGovernanceEnabled()) {
                log.warn("[Auth] Access rejected: no LingContext but LINGCORE governance is enabled. capability={}",
                        capability);
                return false;
            }
            // 灵核治理关闭：视为灵核内部调用，默认放行
            log.debug("[Auth] No LingContext (LINGCORE governance disabled), allowed. capability={}", capability);
            return true;
        }

        boolean allowed = checkInternal(lingId, capability, accessType);
        log.debug("[Auth] Permission table check result: {}", allowed);

        if (!allowed && config.isDevMode()) {
            log.warn("==========================================================================");
            log.warn("[DEV WARNING] ling [{}] unauthorized access [{}] ({}). Please declare in ling.yml: {}",
                    lingId, capability, accessType, capability);
            log.warn("==========================================================================");
            publishDevModeBypassAlert(lingId, capability, accessType);
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

        String traceId = resolveTraceId();
        String callerLingId = record.getCallerLingId();
        String principal = normalize(record.getPrincipal());
        String capability = normalize(record.getCapability());
        String action = truncate(record.getAction(), 80);
        String resource = truncate(record.getResource(), 120);
        String source = truncate(resolveInvocationSource(), 160);
        String ruleSource = truncate(resolveRuleSource(), 120);
        String failureReason = truncate(record.getFailureReason(), 160);

        if (record.getResult() == PermissionAuditResult.DENIED) {
            log.warn("[Security] Access denied - caller={}, capability={}, action={}, resource={}, source={}, ruleSource={}",
                    callerLingId, capability, action, resource, source, ruleSource);
        } else if (record.getResult() == PermissionAuditResult.FAILED) {
            log.warn("[Security] Allowed invocation failed - caller={}, capability={}, action={}, source={}, ruleSource={}, reason={}",
                    callerLingId, capability, action, source, ruleSource, failureReason);
        }

        // 微内核解耦：审计记录通过 EventBus 异步分发，
        // audit 扩展包订阅 AuditLogEvent 自行持久化，security 不直接依赖 audit 包。

        if (eventBus != null) {
            // MonitoringEvents.* 由 EventBus 异步分发。
            // 这里保证事件已发布，但消费方应按最终一致语义处理，而不是假定同步送达。
            eventBus.publish(new MonitoringEvents.AuditLogEvent(
                    traceId,
                    callerLingId,
                    principal,
                    action,
                    resource,
                    capability,
                    source,
                    ruleSource,
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
    public void replacePermissions(String lingId, Map<String, AccessType> newPermissions) {
        // 使用 compute 原子替换，避免「先 removeLing 再逐条 grant」造成的权限真空窗口。
        // compute 返回 null 时自动移除 entry，等价于清空。
        permissions.compute(lingId, (k, existing) ->
                (newPermissions == null || newPermissions.isEmpty())
                        ? null
                        : new ConcurrentHashMap<>(newPermissions));
        log.info("[PermissionService] Atomically replaced permissions for lingId={}, capabilityCount={}",
                lingId, newPermissions == null ? 0 : newPermissions.size());
    }

    @Override
    public boolean isLingCoreGovernanceEnabled() {
        return config.isLingCoreGovernanceEnabled();
    }

    @Override
    public boolean hasCapabilityPrefix(String lingId, String capabilityPrefix) {
        if (lingId == null || capabilityPrefix == null) {
            return false;
        }
        Map<String, AccessType> lingPerms = permissions.get(lingId);
        if (lingPerms == null) {
            return false;
        }
        return lingPerms.keySet().stream()
                .anyMatch(cap -> cap != null && cap.startsWith(capabilityPrefix));
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

    private void publishDevModeBypassAlert(String lingId, String capability, AccessType accessType) {
        if (eventBus == null) {
            return;
        }

        String traceId = resolveTraceId();
        String source = truncate(resolveInvocationSource(), 160);
        String ruleSource = truncate(resolveRuleSource(), 120);
        String message = String.format(
                "Dev mode bypassed unauthorized access for ling [%s]: capability [%s] (%s). Please declare it in ling.yml.",
                lingId,
                capability,
                accessType);
        // Dev 模式告警同样通过异步监控事件投递，适合监控与 Dashboard 消费。
        eventBus.publish(new MonitoringEvents.AlertNotifyEvent(
                traceId,
                "WARNING",
                "DEV_PERMISSION_BYPASS",
                lingId,
                message,
                source,
                ruleSource));
    }

    private String resolveTraceId() {
        InvocationContext ctx = InvocationContext.current();
        String traceId = ctx == null ? null : normalize(ctx.getTraceId());
        if (traceId != null) {
            LingCallContext.setTraceId(traceId);
            return traceId;
        }
        return LingCallContext.startTrace();
    }

    private String resolveInvocationSource() {
        InvocationContext ctx = InvocationContext.current();
        if (ctx == null) {
            return null;
        }

        String service = normalize(ctx.getServiceFQSID());
        String operation = normalize(ctx.getOperation());
        String resource = normalize(ctx.getResourceId());

        if (service != null && operation != null) {
            return service + "#" + operation;
        }
        if (service != null) {
            return service;
        }
        if (operation != null) {
            return operation;
        }
        return resource;
    }

    private String resolveRuleSource() {
        InvocationContext ctx = InvocationContext.current();
        return ctx == null ? null : normalize(ctx.governance().getRuleSource());
    }
}
