package com.lingframe.core.pipeline;

import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.RoutableTarget;
import lombok.Getter;
import lombok.Setter;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 调用上下文：Pipeline 全链路的唯一“通行证”。
 * ⚠️【高危警告：防止 ClassLoader 内存泄漏】⚠️
 * 本对象通过 ThreadLocal 对象池复用，在灵核线程中可能长期存活。
 * 新版架构把协议拆成 routing / resolution / governance / execution 四个显式分区，
 * 目的不是“为了好看”，而是强制把“路由事实”“解析产物”“治理意图”“执行模式”分开，避免再次退回字符串附件驱动。
 * <p>
 * 【铁律 3.0】
 * 1. 顶层字段优先只放 String / long / Map 等基础协议数据。
 * 2. 运行时对象（如 LingRuntime）必须使用 WeakReference。
 * 3. 解析阶段允许短暂持有 ClassLoader / Class<?>[] / Method 等强引用，但只能进入 resolution 分区，
 *    并且必须在 reset() 中物理清空，绝不能跨调用残留。
 * 4. attachments 已降级为扩展附件袋，不再承载核心协议，避免“字符串 key 驱动架构”继续蔓延。
 */
@Getter
@Setter
public class InvocationContext {

    /**
     * 线程局部对象池，减少高频调用时的上下文分配。
     * 支持嵌套调用（例如 Web -> Interceptor -> Proxy）。
     */
    private static final ThreadLocal<Deque<InvocationContext>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * 当前线程的活动上下文。
     * 与对象池独立存在，用于线程传播和嵌套 attach / detach。
     */
    private static final ThreadLocal<InvocationContext> CURRENT = new ThreadLocal<>();

    // ════════════════════════════════════════════
    // 第一部分：基础调用事实
    // ════════════════════════════════════════════
    private String serviceFQSID;
    private String methodName;

    // ⚠️ 绝不能改回 Class<?>[]，否则入口阶段就会把目标灵元的类型系统提前拉进灵核线程
    private String[] parameterTypeNames;
    private Object[] args;
    private String targetLingId;
    private String targetVersion;

    /**
     * FQSID 解析缓存：lingId 部分。
     * <p>
     * 多个 Filter（MacroStateGuard/Resilience/ContextIsolation/ThreadIsolation）
     * 都需要从 fqsid 提取 lingId，缓存避免重复 split。
     */
    private transient String cachedLingId;
    /** FQSID 解析缓存：serviceName 部分 */
    private transient String cachedServiceName;

    /**
     * 运行时对象必须走弱引用。
     * 否则对象池里的 InvocationContext 会把 Runtime 与其背后的灵元 ClassLoader 长期挂住。
     * <p>
     * 路由升维：字段类型从 {@code LingRuntime} 升级为 {@link RoutableTarget}，
     * 使灵核（{@code LingCoreRoutableTarget}）和灵元（{@code LingRuntime}）都能存入。
     * 旧调用方通过 {@code instanceof LingRuntime} 转型获取灵元独有方法。
     */
    private WeakReference<RoutableTarget> runtimeRef;

    // ════════════════════════════════════════════
    // 第二部分：链路身份与治理入参
    // ════════════════════════════════════════════
    private String traceId;
    private String callerLingId;
    private long createTimeNanos;

    private String resourceType;
    private String resourceId;
    private String operation;
    private Map<String, String> labels;
    private Map<String, Object> metadata;

    /**
     * 显式协议分区。
     * ⚠️ 核心链路协议只能写入这些分区，不能再退回 attachments + magic key 模式。
     */
    private final InvocationRoutingState routingState = new InvocationRoutingState();
    private final InvocationResolutionState resolutionState = new InvocationResolutionState();
    private final InvocationGovernanceState governanceState = new InvocationGovernanceState();
    private final InvocationExecutionState executionState = new InvocationExecutionState();

    /**
     * 扩展附件袋。
     * 这里不再承载核心链路协议，只允许作为临时扩展槽位使用。
     * ⚠️ 写入复杂对象时仍要谨慎，最好在 finally 中主动移除。
     */
    private final Map<String, Object> attachments = new HashMap<>();

    private InvocationContext() {
    }

    /**
     * 获取当前线程的活动上下文，不创建新实例。
     */
    public static InvocationContext current() {
        return CURRENT.get();
    }

    /**
     * 获取可复用的上下文实例。
     * ⚠️ 每次取出都必须强制 reset，一旦对象池里带脏数据，后果比多分配一个对象更难排查。
     */
    public static InvocationContext obtain() {
        Deque<InvocationContext> stack = STACK.get();
        InvocationContext ctx = stack.isEmpty() ? new InvocationContext() : stack.pop();
        ctx.reset();
        return ctx;
    }

    /**
     * 将当前上下文挂载到线程。
     *
     * @return 挂载前的活动上下文，用于后续恢复
     */
    public InvocationContext attach() {
        InvocationContext previous = CURRENT.get();
        CURRENT.set(this);
        return previous;
    }

    /**
     * 恢复上一个活动上下文。
     */
    public static void detach(InvocationContext toRestore) {
        if (toRestore != null) {
            CURRENT.set(toRestore);
        } else {
            CURRENT.remove();
        }
    }

    /**
     * 回收上下文到对象池。
     */
    public void recycle() {
        reset();
        STACK.get().push(this);
    }

    /**
     * 显式读取路由分区。
     * 路由分区只描述“应该去哪个实例”，不描述如何解析方法。
     */
    public InvocationRoutingState routing() {
        return routingState;
    }

    /**
     * 显式读取解析分区。
     * 解析分区只承载 ClassLoader / Method 等短生命产物。
     */
    public InvocationResolutionState resolution() {
        return resolutionState;
    }

    /**
     * 显式读取治理分区。
     * 治理分区描述权限、审计、超时等“运维意图”。
     */
    public InvocationGovernanceState governance() {
        return governanceState;
    }

    /**
     * 显式读取执行分区。
     * 执行分区描述本次调用要不要真实落地、要不要采样轨迹。
     */
    public InvocationExecutionState execution() {
        return executionState;
    }

    /**
     * 从 FQSID 中提取 lingId，结果缓存避免重复 split。
     * <p>
     * FQSID 格式为 {@code lingId:serviceName}，多个 Filter 都需要提取 lingId，
     * 缓存后同一次调用只需 split 一次。
     *
     * @return lingId，如果 FQSID 为 null 则返回 null
     */
    public void setServiceFQSID(String serviceFQSID) {
        this.serviceFQSID = serviceFQSID;
        if (serviceFQSID == null) {
            this.cachedLingId = null;
            this.cachedServiceName = null;
        } else {
            int colonIndex = serviceFQSID.indexOf(':');
            if (colonIndex != -1) {
                this.cachedLingId = serviceFQSID.substring(0, colonIndex);
                this.cachedServiceName = serviceFQSID.substring(colonIndex + 1);
            } else {
                this.cachedLingId = serviceFQSID;
                this.cachedServiceName = null;
            }
        }
    }

    /**
     * 从 FQSID 中提取 lingId 部分。
     * <p>
     * 旧格式（{@code lingId:serviceName}）返回真实 lingId。
     * 新格式（{@code __provider__:contractId}）返回占位符 {@code __provider__}——
     * 调用方如需「当前调用的真实目标 lingId」，应优先使用 {@link #getEffectiveLingId()}。
     *
     * @return FQSID 中的 lingId 部分；如果 FQSID 为 null 则返回 null
     */
    public String getLingIdFromFqsid() {
        return cachedLingId;
    }

    /**
     * 获取当前调用的有效目标 lingId。
     * <p>
     * 读路径优先级：
     * <ol>
     *   <li>{@link #getTargetLingId()}——ContractProviderRoutingFilter 在 L0 阶段已解析出真实 lingId</li>
     *   <li>{@link #getLingIdFromFqsid()}——fallback 兼容旧格式 FQSID（{@code lingId:serviceName}）</li>
     * </ol>
     * 新格式 FQSID（{@code __provider__:contractId}）下 {@link #getLingIdFromFqsid()}
     * 返回占位符，必须优先读 {@link #getTargetLingId()}。
     *
     * @return 真实目标 lingId；如果都为 null 则返回 null
     */
    public String getEffectiveLingId() {
        return targetLingId != null ? targetLingId : cachedLingId;
    }

    /**
     * 从 FQSID 中提取 serviceName 部分，结果缓存避免重复 split。
     *
     * @return serviceName，如果 FQSID 为 null 则返回 null
     */
    public String getServiceNameFromFqsid() {
        return cachedServiceName;
    }

    /**
     * 运行时对象使用弱引用，避免上下文池把 Runtime 长时间挂住。
     * <p>
     * 路由升维：参数类型从 {@code LingRuntime} 升级为 {@link RoutableTarget}。
     * 灵元调用方传 {@code LingRuntime}（实现 RoutableTarget），灵核调用方传 {@code LingCoreRoutableTarget}。
     */
    public void setRuntime(RoutableTarget runtime) {
        this.runtimeRef = runtime == null ? null : new WeakReference<>(runtime);
    }

    /**
     * 获取运行时目标。
     * <p>
     * 路由升维：返回类型从 {@code LingRuntime} 升级为 {@link RoutableTarget}。
     * 调用方如需灵元独有方法（如 {@code getConfig}），用 {@code instanceof LingRuntime} 转型。
     */
    public RoutableTarget getRuntime() {
        return runtimeRef == null ? null : runtimeRef.get();
    }

    /**
     * 获取灵元运行时（LingRuntime）。
     * <p>
     * 路由升维：灵核（{@code LingCoreRoutableTarget}）不是 LingRuntime，返回 null。
     * 治理类 Filter 调用此方法：灵核走 null 分支跳过治理。
     *
     * @return 灵元运行时；如果目标为灵核或未设置，返回 null
     */
    public LingRuntime getLingRuntime() {
        RoutableTarget target = getRuntime();
        return target instanceof LingRuntime ? (LingRuntime) target : null;
    }

    /**
     * 清空所有状态，确保对象池中的上下文不会携带上一次调用残留。
     */
    public void reset() {
        this.serviceFQSID = null;
        this.methodName = null;
        this.parameterTypeNames = null;
        this.args = null;
        this.targetLingId = null;
        this.targetVersion = null;
        this.cachedLingId = null;
        this.cachedServiceName = null;
        this.runtimeRef = null; // ⚠️ 物理清空 WeakReference 容器本身，而不只是等待 referent 自己失效

        this.traceId = null;
        this.callerLingId = null;
        this.createTimeNanos = 0L;

        this.resourceType = null;
        this.resourceId = null;
        this.operation = null;
        this.labels = null;
        this.metadata = null;

        this.routingState.reset();
        this.resolutionState.reset();
        this.governanceState.reset();
        this.executionState.reset();

        // attachments 虽已降级为扩展槽位，但对象池回收前仍必须强制清空
        this.attachments.clear();
    }

    /**
     * 从另一个上下文复制状态。
     * 用于线程间传播时构造子线程视角的当前上下文。
     * ⚠️ 这里的复制是“当前调用树内的受控浅拷贝”，不是可长期持有的快照对象。
     */
    public void copyFrom(InvocationContext source) {
        if (source == null) {
            return;
        }
        this.serviceFQSID = source.serviceFQSID;
        this.methodName = source.methodName;
        this.parameterTypeNames = source.parameterTypeNames;
        this.args = source.args;
        this.targetLingId = source.targetLingId;
        this.targetVersion = source.targetVersion;
        this.cachedLingId = source.cachedLingId;
        this.cachedServiceName = source.cachedServiceName;
        this.runtimeRef = source.runtimeRef;

        this.traceId = source.traceId;
        this.callerLingId = source.callerLingId;
        this.createTimeNanos = source.createTimeNanos;

        this.resourceType = source.resourceType;
        this.resourceId = source.resourceId;
        this.operation = source.operation;
        this.labels = source.labels;
        this.metadata = source.metadata;

        this.routingState.copyFrom(source.routingState);
        this.resolutionState.copyFrom(source.resolutionState);
        this.governanceState.copyFrom(source.governanceState);
        this.executionState.copyFrom(source.executionState);
        this.attachments.putAll(source.attachments);
    }

    /**
     * 包装子线程 Callable，传播当前线程的调用上下文和 LingCallContext 快照。
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        InvocationContext parent = InvocationContext.current();
        LingCallContextSnapshot snapshot = LingCallContextSnapshot.capture();
        return () -> {
            InvocationContext child = InvocationContext.obtain();
            InvocationContext previous = child.attach();
            LingCallContextSnapshot previousSnapshot = LingCallContextSnapshot.apply(snapshot);
            try {
                if (parent != null) {
                    child.copyFrom(parent);
                }
                return task.call();
            } finally {
                LingCallContextSnapshot.restore(previousSnapshot);
                InvocationContext.detach(previous);
                child.recycle();
            }
        };
    }

    /**
     * 包装子线程 Runnable，传播当前线程的调用上下文和 LingCallContext 快照。
     */
    public static Runnable wrap(Runnable task) {
        InvocationContext parent = InvocationContext.current();
        LingCallContextSnapshot snapshot = LingCallContextSnapshot.capture();
        return () -> {
            InvocationContext child = InvocationContext.obtain();
            InvocationContext previous = child.attach();
            LingCallContextSnapshot previousSnapshot = LingCallContextSnapshot.apply(snapshot);
            try {
                if (parent != null) {
                    child.copyFrom(parent);
                }
                task.run();
            } finally {
                LingCallContextSnapshot.restore(previousSnapshot);
                InvocationContext.detach(previous);
                child.recycle();
            }
        };
    }
}
