package com.lingframe.core.kernel;

import com.lingframe.api.security.AccessType;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 治理上下文：Core 层唯一认可的流量“通行证”
 * <p>
 * ⚠️【高危警告：防止 ClassLoader 内存泄漏】⚠️
 * 鉴于 {@link com.lingframe.core.proxy.SmartServiceProxy} 采用了基于 ThreadLocal 的零 GC
 * 对象池复用机制，
 * 本对象（InvocationContext）在宿主线程（如 Tomcat Worker）中是长久存活且**不主动 remove** 的。
 * 
 * 绝对严禁在本类中新增任何由单元（Ling）自身 ClassLoader 加载的复杂业务对象字段！
 * 只能使用 JDK 基础类（String, Map, Object[] 等）。
 * 否则，单次跨单元调用的残留引用将导致该单元在卸载时发生严重的 Metaspace ClassLoader OOM！
 * </p>
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