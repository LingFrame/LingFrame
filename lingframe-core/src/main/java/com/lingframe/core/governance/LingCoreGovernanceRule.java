package com.lingframe.core.governance;

import com.lingframe.api.security.AccessType;
import lombok.Builder;
import lombok.Data;

import java.time.Duration;

/**
 * 灵核治理规则 DTO
 */
@Data
@Builder
public class LingCoreGovernanceRule {
    /** 资源匹配模式 (Regex/AntPath) */
    private String pattern;

    /** 必需权限 */
    private String permission;

    /** 访问类型 */
    private AccessType accessType;

    /** 是否审计 */
    private Boolean auditEnabled;

    /** 审计动作名 */
    private String auditAction;

    /** 超时时间 */
    private Duration timeout;

    /** 重试次数 */
    private Integer retryCount;

    /** 降级返回值 (String) */
    private String fallbackValue;
}