package com.lingframe.core.pipeline;

import com.lingframe.api.security.AccessType;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.model.EngineTrace;
import lombok.Data;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * 调用上下文：Pipeline 全链路的唯一"通行证"
 * ⚠️【高危警告：防止 ClassLoader 内存泄漏】⚠️
 * 本对象通过 ThreadLocal 对象池复用，在宿主线程中长久存活。
 * 【铁律 2.0】优先使用 JDK 基础类型。允许持有 Core/API 层的复杂对象引用以提升性能，但遵循以下分级策略：
 * 1. 基础数据载体（如 EngineTrace）：若仅持有 String/int 等 JDK 基础类型且在 reset 时执行 clear，允许持有强引用
 * List。
 * 2. 运行时相关对象（如 LingRuntime / LingInstance）：【强制】必须使用 WeakReference。此类对象关联灵元
 * ClassLoader，强引用会导致卸载后内存泄漏。
 * 3. 对象池安全防护：即便使用弱引用，也必须在 reset 中显式置 null，从物理上断开引用链。
 * 严禁持有任何 Class 对象，严禁在池化对象中长期保存任何灵元实例相关的状态。
 */

@Data
public class InvocationContext {

    // 线程局部上下文栈，支持嵌套调用（如 Web -> Interceptor -> Proxy）
    private static final ThreadLocal<Deque<InvocationContext>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * 获取或创建一个可用的上下文
     */
    public static InvocationContext obtain() {
        Deque<InvocationContext> stack = STACK.get();
        InvocationContext ctx;
        if (stack.isEmpty()) {
            ctx = new InvocationContext();
        } else {
            ctx = stack.pop();
        }
        // 关键防护：取出后强制全量重置，防止脏数据（特别是弱引用失效导致的残留状态）影响下一次调用
        ctx.reset();
        return ctx;
    }

    /**
     * 将当前上下文归还到对象池
     */
    public void recycle() {
        this.reset();
        STACK.get().push(this);
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

    /**
     * 运行时弱引用 (防止灵元卸载后无法回收 ClassLoader)
     */
    private WeakReference<LingRuntime> runtimeRef;

    public void setRuntime(LingRuntime runtime) {
        this.runtimeRef = (runtime != null) ? new WeakReference<>(runtime) : null;
    }

    public LingRuntime getRuntime() {
        return (runtimeRef != null) ? runtimeRef.get() : null;
    }

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

    /**
     * 是否跳过终端调用（末端反射）。
     * 一般用于 Web 这种“只借用治理管道，不借用末端执行”的场景。
     */
    private boolean skipTerminalInvocation;

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
        this.runtimeRef = null; // 物理清除弱引用容器

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
        this.skipTerminalInvocation = false;
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
        this.runtimeRef = source.runtimeRef;

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
                child.recycle();
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
                child.recycle();
            }
        };
    }
}
