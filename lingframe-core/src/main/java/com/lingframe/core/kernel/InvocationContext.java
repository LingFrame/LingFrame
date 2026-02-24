package com.lingframe.core.kernel;

import com.lingframe.api.security.AccessType;
import lombok.Data;
import java.util.HashMap;
import java.util.Map;

/**
 * 调用上下文：Pipeline 全链路的唯一"通行证"
 * ⚠️【高危警告：防止 ClassLoader 内存泄漏】⚠️
 * 本对象通过 ThreadLocal 对象池复用，在宿主线程中长久存活。
 * 【铁律】所有字段必须是 JDK 基础类型。绝对禁止持有由单元 ClassLoader 加载的对象引用！
 */
@Data
public class InvocationContext {

    // 线程局部对象池
    private static final ThreadLocal<InvocationContext> POOL = ThreadLocal.withInitial(InvocationContext::new);

    public static InvocationContext obtain() {
        return POOL.get(); // 获取复用对象
    }

    private InvocationContext() {
        this.attachments = new HashMap<>();
    }

    // ════════════════════════════════════════════
    // 第一部分：调用路由（Pipeline 核心依赖）
    // ════════════════════════════════════════════
    private String serviceFQSID;
    private String methodName;
    private String[] parameterTypeNames; // 绝不能用 Class<?>[]
    private Object[] args;
    private String targetLingId;
    private String targetVersion;

    // ════════════════════════════════════════════
    // 第二部分：链路追踪与身份
    // ════════════════════════════════════════════
    private String traceId;
    private String callerLingId;
    private long createTimeNanos;

    // ════════════════════════════════════════════
    // 第三部分：治理决策
    // ════════════════════════════════════════════
    private String resourceType;
    private String resourceId;
    private String operation;
    private String requiredPermission;
    private AccessType accessType;
    private String auditAction;
    private boolean shouldAudit;
    private String ruleSource;

    // ════════════════════════════════════════════
    // 第四部分：路由与弹性治理
    // ════════════════════════════════════════════
    private Map<String, String> labels;
    private Integer timeout;
    private Map<String, Object> metadata;

    // ════════════════════════════════════════════
    // 第五部分：Filter 间瞬态通信
    // ════════════════════════════════════════════
    // ⚠️ 写入 attachments 的复杂对象引用必须在 finally 中主动移除！
    private Map<String, Object> attachments;

    /** 重置所有字段，防止污染下一次调用 */
    public void reset() {
        this.serviceFQSID = null;
        this.methodName = null;
        this.parameterTypeNames = null;
        this.args = null;
        this.targetLingId = null;
        this.targetVersion = null;

        this.traceId = null;
        this.callerLingId = null;
        this.createTimeNanos = 0L;

        this.resourceType = null;
        this.resourceId = null;
        this.operation = null;
        this.requiredPermission = null;
        this.accessType = null;
        this.auditAction = null;
        this.shouldAudit = false;
        this.ruleSource = null;

        this.labels = null;
        this.timeout = null;
        this.metadata = null;

        if (this.attachments != null) {
            this.attachments.clear();
        }
    }

    // =========================================================================
    // 为保障 M1 阶段旧组件(GovernanceKernel/SmartServiceProxy/Adapters)正常编译，
    // 临时保留的向下兼容 API (M3 阶段彻底删除)
    // =========================================================================

    @Deprecated
    public String getLingId() {
        return this.targetLingId;
    }

    @Deprecated
    public void setLingId(String lingId) {
        this.targetLingId = lingId;
    }

    @Deprecated
    public static Builder builder() {
        return new Builder(obtain());
    }

    @Deprecated
    public static class Builder {
        private final InvocationContext ctx;

        private Builder(InvocationContext ctx) {
            this.ctx = ctx;
        }

        public Builder traceId(String traceId) {
            ctx.setTraceId(traceId);
            return this;
        }

        public Builder callerLingId(String callerLingId) {
            ctx.setCallerLingId(callerLingId);
            return this;
        }

        public Builder targetLingId(String targetLingId) {
            ctx.setTargetLingId(targetLingId);
            return this;
        }

        public Builder lingId(String lingId) {
            ctx.setLingId(lingId);
            return this;
        }

        public Builder serviceFQSID(String serviceFQSID) {
            ctx.setServiceFQSID(serviceFQSID);
            return this;
        }

        public Builder methodName(String methodName) {
            ctx.setMethodName(methodName);
            return this;
        }

        public Builder parameterTypeNames(String[] parameterTypeNames) {
            ctx.setParameterTypeNames(parameterTypeNames);
            return this;
        }

        public Builder args(Object[] args) {
            ctx.setArgs(args);
            return this;
        }

        public Builder targetVersion(String targetVersion) {
            ctx.setTargetVersion(targetVersion);
            return this;
        }

        public Builder createTimeNanos(long createTimeNanos) {
            ctx.setCreateTimeNanos(createTimeNanos);
            return this;
        }

        public Builder resourceType(String resourceType) {
            ctx.setResourceType(resourceType);
            return this;
        }

        public Builder resourceId(String resourceId) {
            ctx.setResourceId(resourceId);
            return this;
        }

        public Builder operation(String operation) {
            ctx.setOperation(operation);
            return this;
        }

        public Builder requiredPermission(String requiredPermission) {
            ctx.setRequiredPermission(requiredPermission);
            return this;
        }

        public Builder accessType(AccessType accessType) {
            ctx.setAccessType(accessType);
            return this;
        }

        public Builder auditAction(String auditAction) {
            ctx.setAuditAction(auditAction);
            return this;
        }

        public Builder shouldAudit(boolean shouldAudit) {
            ctx.setShouldAudit(shouldAudit);
            return this;
        }

        public Builder ruleSource(String ruleSource) {
            ctx.setRuleSource(ruleSource);
            return this;
        }

        public Builder labels(Map<String, String> labels) {
            ctx.setLabels(labels);
            return this;
        }

        public Builder timeout(Integer timeout) {
            ctx.setTimeout(timeout);
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            ctx.setMetadata(metadata);
            return this;
        }

        public Builder attachments(Map<String, Object> attachments) {
            ctx.setAttachments(attachments);
            return this;
        }

        public InvocationContext build() {
            return ctx;
        }
    }
}
