package com.lingframe.api.security;

import lombok.Builder;
import lombok.Getter;

/**
 * 权限治理调用使用的结构化审计记录。
 */
@Getter
@Builder
public class PermissionAuditRecord {

    private final String callerLingId;
    private final String principal;
    private final String capability;
    private final String action;
    private final String resource;
    private final PermissionAuditResult result;
    private final String failureReason;
    private final long costNanos;
}
