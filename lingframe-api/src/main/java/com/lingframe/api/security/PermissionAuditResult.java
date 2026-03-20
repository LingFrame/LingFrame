package com.lingframe.api.security;

/**
 * 显式的权限审计结果协议。
 */
public enum PermissionAuditResult {
    ALLOWED,
    DENIED,
    FAILED;

    public boolean isSuccess() {
        return this == ALLOWED;
    }
}
