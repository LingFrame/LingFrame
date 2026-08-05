package com.lingframe.core.pipeline;

import com.lingframe.api.security.AccessType;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.ling.LingInstance;

import lombok.extern.slf4j.Slf4j;

/**
 * 灵元调用上下文构造器。
 * <p>
 * 收束散在 dashboard / 灵核 / runtime 业务类中的 {@link InvocationContext} 手工拼装，
 * 让外围模块只描述「我要模拟什么 / 调用什么」，不再散设置字段或自行决定执行模式。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>只暴露治理语义相关字段（resourceType / accessType / requiredPermission / auditAction）</li>
 *   <li>不暴露 {@code resolution()} 分区——那是 Web/AOP 适配层特有需求，留给他们直接操作 {@link InvocationContext}</li>
 *   <li>{@code build()} 完成后补全 runtime 引用与目标灵元身份，减少调用方拼装负担</li>
 * </ul>
 *
 * @see InvocationPipelineEngine#invoke(InvocationContext)
 */
@Slf4j
public final class InvocationContextBuilder {

    private final InvocationContext ctx;
    private final InvocationExecutionMode mode;

    private InvocationContextBuilder(InvocationExecutionMode mode) {
        this.ctx = InvocationContext.obtain();
        this.mode = mode;
        ctx.execution().setMode(mode);
    }

    /**
     * 模拟模式构造器。
     * <p>
     * 用于 Dashboard 模拟场景——跑完整治理链但不真实执行终端。
     *
     * @param lingId 目标灵元 ID
     * @return 新构造器
     */
    public static InvocationContextBuilder forSimulation(String lingId) {
        InvocationContextBuilder b = new InvocationContextBuilder(InvocationExecutionMode.SIMULATION);
        b.ctx.setTargetLingId(lingId);
        b.ctx.setCallerLingId(lingId); // 模拟自身调用
        return b;
    }

    /**
     * 契约级模拟模式构造器（压测专用）。
     * <p>
     * 与 {@link #forSimulation} 的区别：<strong>不锁定 targetLingId</strong>，
     * 仅以裸契约 ID 作为入口，让 {@code ContractProviderRoutingFilter} 的 L0 provider 级路由
     * 在全部候选（含灵核 baseline）之间按权重选路。这是验证「N 元权重分流」的关键——
     * 锁死 targetLingId 会令 L0 分支直接放行（入口已锁定灵元时不覆盖入口意图），压测退化为空转。
     *
     * @param callerLingId  调用方灵元 ID（压测场景为被测灵元自身）
     * @param bareContractId 裸契约 ID（不含 {@code lingId:} 前缀）
     * @return 新构造器
     */
    public static InvocationContextBuilder forContractSimulation(String callerLingId, String bareContractId) {
        InvocationContextBuilder b = new InvocationContextBuilder(InvocationExecutionMode.SIMULATION);
        b.ctx.setCallerLingId(callerLingId);
        b.ctx.setServiceFQSID(bareContractId);
        return b;
    }

    /**
     * 仅治理模式构造器。
     * <p>
     * 用于 Dashboard 预检场景——只跑治理不真实执行。
     *
     * @param lingId 目标灵元 ID
     * @return 新构造器
     */
    public static InvocationContextBuilder forGovernOnly(String lingId) {
        InvocationContextBuilder b = new InvocationContextBuilder(InvocationExecutionMode.GOVERN_ONLY);
        b.ctx.setTargetLingId(lingId);
        return b;
    }

    /**
     * 正常调用模式构造器。
     * <p>
     * 用于灵核朴素调用——按 serviceFQSID 路由，真实执行终端。
     *
     * @param callerLingId  调用方灵元 ID
     * @param serviceFQSID  目标服务全限定 ID（lingId:serviceName）
     * @return 新构造器
     */
    public static InvocationContextBuilder forNormal(String callerLingId, String serviceFQSID) {
        InvocationContextBuilder b = new InvocationContextBuilder(InvocationExecutionMode.NORMAL);
        b.ctx.setCallerLingId(callerLingId);
        b.ctx.setServiceFQSID(serviceFQSID);
        // setServiceFQSID 内部已提取 cachedLingId，直接复用避免重复解析
        String lingId = b.ctx.getLingIdFromFqsid();
        if (lingId != null) {
            b.ctx.setTargetLingId(lingId);
        }
        return b;
    }

    public InvocationContextBuilder resourceType(String type) {
        ctx.setResourceType(type);
        return this;
    }

    public InvocationContextBuilder resourceId(String id) {
        ctx.setResourceId(id);
        return this;
    }

    public InvocationContextBuilder operation(String op) {
        ctx.setOperation(op);
        return this;
    }

    public InvocationContextBuilder accessType(AccessType type) {
        ctx.governance().setAccessType(type);
        return this;
    }

    public InvocationContextBuilder requiredPermission(String perm) {
        ctx.governance().setRequiredPermission(perm);
        return this;
    }

    public InvocationContextBuilder auditAction(String action) {
        ctx.governance().setShouldAudit(true);
        ctx.governance().setAuditAction(action);
        return this;
    }

    public InvocationContextBuilder callerLingId(String id) {
        ctx.setCallerLingId(id);
        return this;
    }

    public InvocationContextBuilder targetVersion(String version) {
        ctx.setTargetVersion(version);
        return this;
    }

    public InvocationContextBuilder traceId(String traceId) {
        ctx.setTraceId(traceId);
        return this;
    }

    public InvocationContextBuilder methodName(String methodName) {
        ctx.setMethodName(methodName);
        return this;
    }

    /**
     * 构造上下文。
     * <p>
     * 若 Repository 可用，补全 runtime 引用，避免调用方再拼一道。
     *
     * @param repository 灵元仓库（可为 null，模拟未装载灵元时）
     * @return 已就绪的调用上下文
     */
    public InvocationContext build(LingRepository repository) {
        if (repository != null && ctx.getRuntime() == null) {
            LingRuntime runtime = repository.getRuntime(ctx.getTargetLingId());
            if (runtime != null) {
                ctx.setRuntime(runtime);
            }
        }
        return ctx;
    }

    /**
     * 构造上下文（无 runtime 绑定）。
     * <p>
     * 用于不需要 runtime 的纯治理预检场景。
     */
    public InvocationContext build() {
        return build(null);
    }
}
