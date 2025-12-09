package com.lingframe.core.strategy;

import com.lingframe.api.security.AccessType;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 治理策略推导器
 * 核心逻辑：基于命名约定自动生成治理规则，实现零侵入式权限管理
 */
public class GovernanceStrategy {

    private static final Map<String, AccessType> PREFIX_MAP = new HashMap<>();

    static {
        // 读操作 (READ)
        register(AccessType.READ, "get", "find", "query", "list", "select", "count", "check", "is", "has");
        // 写操作 (WRITE)
        register(AccessType.WRITE, "create", "save", "insert", "update", "modify", "delete", "remove", "add", "set");
    }

    private static void register(AccessType type, String... prefixes) {
        for (String p : prefixes) PREFIX_MAP.put(p, type);
    }

    /**
     * 推导权限标识
     * 规则：ServiceName + : + AccessType (如 "UserService:READ")
     */
    public static String inferPermission(Method method) {
        String resourceName = method.getDeclaringClass().getSimpleName();
        AccessType type = inferAccessType(method.getName());
        return resourceName + ":" + type.name();
    }

    /**
     * 推导操作类型 (默认兜底为 EXECUTE)
     */
    public static AccessType inferAccessType(String methodName) {
        for (Map.Entry<String, AccessType> entry : PREFIX_MAP.entrySet()) {
            if (methodName.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return AccessType.EXECUTE; // 无法识别的方法视为高风险操作
    }

    /**
     * 推导审计动作
     */
    public static String inferAuditAction(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }
}