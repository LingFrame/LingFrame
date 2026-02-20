package com.lingframe.core.kernel;

import com.lingframe.api.security.AccessType;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 治理上下文：Core 层唯一认可的流量“通行证”
 */
@Data
@Builder
public class InvocationContext {
    // 身份信息
    private String traceId; // 全局链路追踪 ID
    private String lingId; // 目标单元 ID
    private String callerLingId; // 调用方单元 ID

    // 资源信息
    private String resourceType; // "WEB" 或 "RPC"
    private String resourceId; // URL (如 /user/create) 或 ServiceID (如 user:create)
    private String operation; // 具体操作 (GET/POST 或 方法名)

    // 智能治理元数据 (由 Adapter 推导后填入)
    private String requiredPermission; // 必须具备的权限标识 (例如 "user:create")
    private AccessType accessType; // 访问类型 (EXECUTE, READ, WRITE)
    private String auditAction; // 审计动作描述 (例如 "CreateUser")
    private boolean shouldAudit; // 是否需要审计
    private String ruleSource; // 规则来源 (Rule Source)

    // 现场数据
    private Object[] args; // 参数
    private Map<String, Object> metadata; // 扩展信息 (IP, UserAgent, RPC Context)

    // 路由标签，用于金丝雀、租户隔离等逻辑
    private Map<String, String> labels;

    // 弹性治理参数
    private Integer timeout; // 超时时间 (ms)
}