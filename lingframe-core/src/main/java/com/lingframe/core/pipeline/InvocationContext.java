package com.lingframe.core.pipeline;

import com.lingframe.core.model.EngineTrace;
import com.lingframe.api.security.AccessType;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 调用上下文：Pipeline 全链路的唯一"通行证"
 * ⚠️【高危警告：防止 ClassLoader 内存泄漏】⚠️
 * 本对象通过 ThreadLocal 对象池复用，在宿主线程中长久存活。
 * 【铁律】所有字段必须是 JDK 基础类型。绝对禁止持有由灵元 ClassLoader 加载的对象引用！
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
    // 第三部分：治理决策与运行推演 (Dry-Run & Trace)
    // ════════════════════════════════════════════
    private String resourceType;
    private String resourceId;
    private String operation;
    private String requiredPermission;
    private AccessType accessType;
    private String auditAction;
    private boolean shouldAudit;
    private String ruleSource;

    // ----- 干跑与追踪（流量回放核心） -----
    /** 是否为干跑/模拟模式。开启后将在最后一环被拦截，不产生真实副作用 */
    private boolean dryRun;
    /**
     * 运行轨迹。只在干跑或特定需要强审计时采集。
     * 由于 ThreadLocal 复用，应尽量复用 List 对象。
     */
    private List<EngineTrace> traces;

    /** 快捷添加追踪的方法 */
    public void addTrace(EngineTrace trace) {
        if (this.traces == null) {
            this.traces = new ArrayList<>();
        }
        this.traces.add(trace);
    }

    // ════════════════════════════════════════════
    // 第四部分：路由与弹性治理
    // ════════════════════════════════════════════
    private Map<String, String> labels;

    public Map<String, String> getLabels() {
        return labels;
    }

    private Integer timeout;
    private Map<String, Object> metadata;

    // ════════════════════════════════════════════
    // 第五部分：Filter 间瞬态通信
    // ════════════════════════════════════════════
    // ⚠️ 写入 attachments 的复杂对象引用必须在 finally 中主动移除！
    private Map<String, Object> attachments;

    public Map<String, Object> getAttachments() {
        return attachments;
    }

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

        this.dryRun = false;
        if (this.traces != null) {
            this.traces.clear();
        }

        this.labels = null;
        this.timeout = null;
        this.metadata = null;

        if (this.attachments != null) {
            this.attachments.clear();
        }
    }

    // ════════════════════════════════════════════
    // 第六部分：线程上下文快照与传播（零分配模式）
    // ════════════════════════════════════════════

    /**
     * 从另一个上下文安全拷贝属性（复用当前对象池实例）
     * 对于基础属性采用赋值，对于预初始化的集合利用 putAll 避免 new
     */
    public void copyFrom(InvocationContext source) {
        if (source == null)
            return;
        this.serviceFQSID = source.serviceFQSID;
        this.methodName = source.methodName;
        this.parameterTypeNames = source.parameterTypeNames;
        this.args = source.args;
        this.targetLingId = source.targetLingId;
        this.targetVersion = source.targetVersion;

        this.traceId = source.traceId;
        this.callerLingId = source.callerLingId;
        this.createTimeNanos = source.createTimeNanos;

        this.resourceType = source.resourceType;
        this.resourceId = source.resourceId;
        this.operation = source.operation;
        this.requiredPermission = source.requiredPermission;
        this.accessType = source.accessType;
        this.auditAction = source.auditAction;
        this.shouldAudit = source.shouldAudit;
        this.ruleSource = source.ruleSource;

        this.dryRun = source.dryRun;
        if (source.traces != null && !source.traces.isEmpty()) {
            if (this.traces == null) {
                this.traces = new ArrayList<>();
            }
            this.traces.addAll(source.traces);
        }

        this.labels = source.labels;
        this.timeout = source.timeout;
        this.metadata = source.metadata;

        if (source.attachments != null && !source.attachments.isEmpty()) {
            this.attachments.putAll(source.attachments);
        }
    }

    /**
     * 将父线程的上下文无锁装载进子线程（用于 Callable）
     * 依赖 copyFrom 达成极致的对象池复用且无内存分配（Zero Allocation）
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        InvocationContext parent = InvocationContext.obtain();
        return () -> {
            InvocationContext child = InvocationContext.obtain();
            try {
                child.copyFrom(parent);
                return task.call();
            } finally {
                child.reset();
            }
        };
    }

    /**
     * 将父线程的上下文无锁装载进子线程（用于 Runnable）
     */
    public static Runnable wrap(Runnable task) {
        InvocationContext parent = InvocationContext.obtain();
        return () -> {
            InvocationContext child = InvocationContext.obtain();
            try {
                child.copyFrom(parent);
                task.run();
            } finally {
                child.reset();
            }
        };
    }
}
