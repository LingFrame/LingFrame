package com.lingframe.core.strategy;

import com.lingframe.api.security.AccessType;

import java.lang.reflect.Method;
import java.util.*;

/**
 * 治理策略推导器
 * 核心逻辑：基于命名约定自动生成治理规则，实现零侵入式权限管理
 */
public class GovernanceStrategy {

    // 改用 Set 存储，contains 比遍历 Entry 更快，且语义更清晰
    private static final Set<String> READ_PREFIXES = new HashSet<>();
    private static final Set<String> WRITE_PREFIXES = new HashSet<>();

    static {
        Collections.addAll(READ_PREFIXES, "get", "find", "query", "list", "select", "count", "check", "is", "has");
        Collections.addAll(WRITE_PREFIXES, "create", "save", "insert", "update", "modify", "delete", "remove", "add", "set");
    }

    /**
     * 推导权限标识
     * 规则：ServiceName + : + AccessType (如 "UserService:READ")
     */
    public static String inferPermission(Method method) {
        if (method == null) return "default:unknown";
        String resourceName = method.getDeclaringClass().getSimpleName();
        AccessType type = inferAccessType(method.getName());
        return resourceName + ":" + type.name();
    }

    /**
     * 推导操作类型 (默认兜底为 EXECUTE)
     */
    public static AccessType inferAccessType(String methodName) {
        // 0. 优先级最高：高危后缀（如 Instance, Factory），强制视为高风险操作
        String lowerName = methodName.toLowerCase();
        if (lowerName.contains("instance") || lowerName.contains("factory")
                || lowerName.contains("builder") || lowerName.contains("create")) {
            return AccessType.WRITE; // 或者 EXECUTE
        }

        // 1. Write 检查 (优先检查，因为写权限更敏感)
        for (String p : WRITE_PREFIXES) {
            if (lowerName.startsWith(p)) return AccessType.WRITE;
        }

        // 2. Read 检查
        for (String p : READ_PREFIXES) {
            if (lowerName.startsWith(p)) return AccessType.READ;
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