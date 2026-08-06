package com.lingframe.starter.interceptor;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.constant.LingCoreConstants;
import com.lingframe.api.context.LingCallContext;
import com.lingframe.api.exception.PermissionDeniedException;
import com.lingframe.api.security.AccessType;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.pipeline.InvocationExecutionMode;
import com.lingframe.core.pipeline.InvocationPipelineEngine;
import com.lingframe.core.governance.GovernanceStrategy;
import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.starter.governance.EntryInvocationGovernanceResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.util.HashMap;

/**
 * 灵核 Bean 治理拦截器
 * 拦截灵核应用中的业务 Bean 方法调用，进行权限检查和审计
 * 支持通过注解（@RequiresPermission、@Auditable）进行配置
 */
@Slf4j
@RequiredArgsConstructor
public class LingCoreBeanGovernanceInterceptor implements MethodInterceptor {

    private final InvocationPipelineEngine pipelineEngine;
    private final boolean governInternalCalls;
    private final boolean checkPermissions;
    private final EntryInvocationGovernanceResolver invocationGovernanceResolver;

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Object[] args = invocation.getArguments();

        log.debug("[Governance Interceptor] Intercepting method: {}.{}",
                method.getDeclaringClass().getSimpleName(), method.getName());

        // 如果是 Object 的基础方法，直接放行
        if (isObjectMethod(method.getName(), method.getParameterTypes())) {
            log.debug("[Governance Interceptor] Skipping Object method: {}", method.getName());
            return invocation.proceed();
        }

        // 获取调用方（当前灵元ID）
        String callerLingId = LingCallContext.getLingId();
        // 如果没有灵元上下文，说明是灵核内部调用
        if (callerLingId == null) {
            // 如果配置为不对灵核内部调用进行治理，直接放行
            if (!governInternalCalls) {
                log.debug("[Governance Interceptor] Internal LINGCORE call, governance disabled, skipping");
                return invocation.proceed();
            }
            callerLingId = LingCoreConstants.LINGCORE_LING_ID;
            log.debug("[Governance Interceptor] No ling context, using LINGCORE as caller: {}", callerLingId);
        } else {
            log.debug("[Governance Interceptor] ling {} calling LINGCORE method: {}.{}",
                    callerLingId, method.getDeclaringClass().getSimpleName(), method.getName());
        }

        // 如果配置为不对灵核应用进行权限检查，灵核 caller 直接放行；灵元 caller 不跳（走 pipelineEngine 治理）
        if (LingCoreConstants.LINGCORE_LING_ID.equals(callerLingId) && !checkPermissions) {
            log.debug("[Governance Interceptor] LINGCORE app, permission check disabled, proceeding");
            return invocation.proceed();
        }

        log.info("[Governance Interceptor] Applying governance to method: {}.{} from ling: {}",
                method.getDeclaringClass().getSimpleName(), method.getName(), callerLingId);

        // 构建治理上下文
        InvocationContext ctx = buildInvocationContext(method, args, callerLingId);
        // 【关键】开启穿刺模式：这里只借道 Pipeline 做治理，不借道 Pipeline 做终端执行。
        // 真正的业务方法仍由当前 AOP 调用链自己 invocation.proceed()。
        ctx.execution().setMode(InvocationExecutionMode.GOVERN_ONLY);

        try {
            // 借道 Pipeline 执行全套治理（并发统计、状态检查、权限校验、审计等）
            pipelineEngine.invoke(ctx);

            // 治理通过，执行业务方法
            return invocation.proceed();
        } catch (LingInvocationException e) {
            // 治理拒绝：卸载/停机/限流期间降级为 info 避免压测日志风暴，权限错误保持 warn
            if (e.getKind() == LingInvocationException.ErrorKind.SECURITY_REJECTED) {
                log.warn("[Governance] Security rejected for Bean: {} -> {}", ctx.getResourceId(), e.getMessage());
                throw new PermissionDeniedException(callerLingId, ctx.governance().getRequiredPermission(), ctx.governance().getAccessType());
            }
            log.info("[Governance] Bean request blocked: {} -> {}", ctx.getResourceId(), e.getMessage());
            throw e;
        } finally {
            ctx.recycle();
        }
    }

    /**
     * 构建治理上下文
     */
    private InvocationContext buildInvocationContext(Method method, Object[] args, String callerLingId) {
        // 智能权限推导
        String permission = null;
        RequiresPermission permAnn = AnnotatedElementUtils.findMergedAnnotation(method, RequiresPermission.class);
        if (permAnn != null) {
            permission = permAnn.value();
        } else {
            // 如果没有注解，根据方法名推导
            permission = GovernanceStrategy.inferPermission(method);
        }

        // 智能审计推导
        boolean shouldAudit = false;
        String auditAction = method.getName();
        Auditable auditAnn = AnnotatedElementUtils.findMergedAnnotation(method, Auditable.class);
        if (auditAnn != null) {
            shouldAudit = true;
            auditAction = auditAnn.action();
        } else {
            // 默认审计写操作
            String methodName = method.getName();
            if (methodName.startsWith("create") || methodName.startsWith("update") ||
                    methodName.startsWith("delete") || methodName.startsWith("save") ||
                    methodName.startsWith("add") || methodName.startsWith("remove")) {
                shouldAudit = true;
            }
        }

        // 推导访问类型
        AccessType accessType = GovernanceStrategy.inferAccessType(method.getName());

        // 构建上下文
        InvocationContext ctx = InvocationContext.obtain();
        ctx.setTraceId(LingCallContext.getTraceId());
        ctx.setTargetLingId(LingCoreConstants.LINGCORE_LING_ID); // 这里写目标标识，而不是旧字段语义上的 lingId
        ctx.setCallerLingId(callerLingId);
        ctx.setServiceFQSID(LingCoreConstants.LINGCORE_LING_ID + ":" + method.getDeclaringClass().getName());
        ctx.setResourceType("RPC");
        ctx.setResourceId(method.getDeclaringClass().getSimpleName() + "." + method.getName());
        ctx.setOperation(method.getName());
        ctx.setMethodName(method.getName());
        ctx.setParameterTypeNames(resolveParameterTypeNames(method));
        ctx.governance().setRequiredPermission(permission);
        ctx.governance().setAccessType(accessType);
        ctx.governance().setAuditAction(auditAction);
        ctx.governance().setShouldAudit(shouldAudit);
        ctx.setArgs(args);
        ctx.setMetadata(new HashMap<>());
        ctx.setLabels(new HashMap<>());
        ctx.governance().setRuleSource(null); // 这里尚未进入规则仲裁阶段，因此显式置空
        if (invocationGovernanceResolver != null) {
            invocationGovernanceResolver.applyTo(ctx, LingCoreConstants.LINGCORE_LING_ID);
        }

        // 入口已经拿到了 Method 元信息，就直接喂给 resolution 分区，后续治理与终端无需重复猜测
        ctx.resolution().setTargetClassName(method.getDeclaringClass().getName());
        ctx.resolution().setResolvedParameterTypes(method.getParameterTypes());
        ctx.resolution().setTargetClassLoader(method.getDeclaringClass().getClassLoader());
        return ctx;
    }

    private String[] resolveParameterTypeNames(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes == null || parameterTypes.length == 0) {
            return new String[0];
        }
        String[] names = new String[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            names[i] = parameterTypes[i].getName();
        }
        return names;
    }

    /**
     * 判断是否为 Object 的基础方法。
     *
     * <p>精确匹配方法签名（名称 + 参数类型），避免用户业务方法名为 toString 等被误跳过。
     * Object 的方法签名固定：
     * <ul>
     *   <li>toString() / hashCode() / getClass() —— 无参</li>
     *   <li>equals(Object) —— 单参 Object</li>
     * </ul>
     */
    private boolean isObjectMethod(String name, Class<?>[] paramTypes) {
        if (paramTypes.length == 0) {
            return "toString".equals(name) || "hashCode".equals(name) || "getClass".equals(name);
        }
        if (paramTypes.length == 1 && paramTypes[0] == Object.class) {
            return "equals".equals(name);
        }
        return false;
    }
}
