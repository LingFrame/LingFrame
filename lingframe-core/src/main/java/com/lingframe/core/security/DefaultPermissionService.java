package com.lingframe.core.security;

import com.lingframe.api.security.AccessType;
import com.lingframe.api.security.PermissionInfo;
import com.lingframe.api.security.PermissionService;
import com.lingframe.core.audit.AuditManager;
import com.lingframe.core.config.LingFrameConfig;
import com.lingframe.core.event.EventBus;
import com.lingframe.core.event.monitor.MonitoringEvents;
import com.lingframe.core.monitor.TraceContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认权限服务实现
 * 职责：管理权限策略，提供鉴权查询，记录审计日志
 */
@Slf4j
public class DefaultPermissionService implements PermissionService {

    private final EventBus eventBus;

    public DefaultPermissionService(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    // 简单的权限表: Map<LingId, Map<Capability, AccessType>>
    // 实际生产中应从数据库或配置文件加载
    private final Map<String, Map<String, AccessType>> permissions = new ConcurrentHashMap<>();

    // 全局白名单服务 (例如基础的日志服务)
    private static final String GLOBAL_WHITELIST_PREFIX = "com.lingframe.api.";

    // 灵核应用 ID
    private static final String HOST_Ling_ID = "lingcore-app";

    @Override
    public boolean isAllowed(String lingId, String capability, AccessType accessType) {
        log.debug("[Auth] Checking permission: lingId={}, capability={}, accessType={}", lingId, capability,
                accessType);

        // 灵核应用根据配置决定是否进行权限检查
        if (HOST_Ling_ID.equals(lingId)) {
            // 如果配置为不检查灵核应用权限，直接放行
            if (!LingFrameConfig.current().isHostCheckPermissions()) {
                log.debug("[Auth] LINGCORE application bypassed");
                return true;
            }
        }

        // 白名单放行
        if (lingId == null || capability.startsWith(GLOBAL_WHITELIST_PREFIX)) {
            log.debug("[Auth] Whitelist bypassed");
            return true;
        }

        // 查表鉴权
        boolean allowed = checkInternal(lingId, capability, accessType);
        log.info("[Auth] Permission table check result: {}", allowed);

        // 开发模式兜底
        if (!allowed && LingFrameConfig.current().isDevMode()) {
            log.warn("==========================================================================");
            log.warn("[DEV WARNING] ling [{}] unauthorized access [{}] ({}). Please declare in ling.yml: {}",
                    lingId, capability, accessType, capability);
            log.warn("==========================================================================");
            return true; // 开发模式强制放行
        }

        return allowed;
    }

    private boolean checkInternal(String lingId, String capability, AccessType accessType) {
        Map<String, AccessType> lingPerms = permissions.get(lingId);
        log.info("[Auth-Internal] lingId={}, table content: {}", lingId, lingPerms);

        if (lingPerms == null) {
            log.info("[Auth-Internal] ling has no permissions -> false");
            return false;
        }

        AccessType granted = lingPerms.get(capability);
        log.info("[Auth-Internal] Query capability={}, granted: {}", capability, granted);

        if (granted == null) {
            log.info("[Auth-Internal] Capability not granted -> false");
            return false;
        }

        // 使用 AccessType 的层级比较方法
        boolean result = granted.satisfies(accessType);
        log.info("[Auth-Internal] granted({}).satisfies(required({})) = {}", granted, accessType, result);
        return result;
    }

    @Override
    public void grant(String lingId, String capability, AccessType accessType) {
        log.info("[PermissionService] Granting permission: lingId={}, capability={}, accessType={}",
                lingId, capability, accessType);
        permissions.computeIfAbsent(lingId, k -> new ConcurrentHashMap<>())
                .put(capability, accessType);
        log.info("[PermissionService] Permission saved, current permissions: {}", permissions.get(lingId));
    }

    @Override
    public void revoke(String lingId, String capability) {
        log.info("[PermissionService] Revoking permission: lingId={}, capability={}", lingId, capability);
        permissions.computeIfAbsent(lingId, k -> new ConcurrentHashMap<>())
                .put(capability, AccessType.NONE);
        log.info("[PermissionService] Permission set to NONE, current permissions: {}", permissions.get(lingId));
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
        // 日志记录
        if (!allowed) {
            log.warn("[Security] Access Denied - ling: {}, Capability: {}, Operation: {}", lingId, capability,
                    operation);
        }

        // 优先从链路上下文获取 TraceId，保持追踪连贯性
        String traceId = TraceContext.start();

        // 1. 持久化审计记录 (异步写入日志/ES/DB)
        AuditManager.asyncRecord(
                traceId,
                lingId,
                allowed ? "ALLOWED" : "DENIED",
                capability,
                new Object[] { truncateOperation(operation) },
                allowed ? "Success" : "Denied",
                0L);

        // 2. 发布审计事件到 EventBus，供 Dashboard 实时展示
        if (eventBus != null) {
            eventBus.publish(new MonitoringEvents.AuditLogEvent(
                    traceId,
                    lingId,
                    allowed ? "ALLOWED" : "DENIED",
                    capability + ":" + truncateOperation(operation),
                    allowed,
                    0L));
        }
    }

    /**
     * 截断过长的 SQL 语句
     */
    private String truncateOperation(String operation) {
        if (operation == null)
            return "";
        return operation.length() > 80 ? operation.substring(0, 80) + "..." : operation;
    }

    /**
     * 清理单元的权限数据
     */
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
}