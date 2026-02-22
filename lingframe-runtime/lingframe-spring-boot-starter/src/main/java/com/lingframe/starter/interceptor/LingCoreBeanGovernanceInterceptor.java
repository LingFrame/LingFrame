package com.lingframe.starter.interceptor;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.context.LingContextHolder;
import com.lingframe.api.security.AccessType;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.monitor.TraceContext;
import com.lingframe.core.strategy.GovernanceStrategy;
import com.lingframe.core.exception.InvocationException;
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

    private final GovernanceKernel governanceKernel;
    private final boolean governInternalCalls;
    private final boolean checkPermissions;
    private static final String HOST_Ling_ID = "lingcore-app";

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Object[] args = invocation.getArguments();

        log.debug("[Governance Interceptor] Intercepting method: {}.{}",
                method.getDeclaringClass().getSimpleName(), method.getName());

        // 如果是 Object 的基础方法，直接放行
        if (isObjectMethod(method.getName())) {
            log.debug("[Governance Interceptor] Skipping Object method: {}", method.getName());
            return invocation.proceed();
        }

        // 获取调用方（当前单元ID）
        String callerLingId = LingContextHolder.get();
        // 如果没有单元上下文，说明是灵核内部调用
        if (callerLingId == null) {
            // 如果配置为不对灵核内部调用进行治理，直接放行
            if (!governInternalCalls) {
                log.debug("[Governance Interceptor] Internal LINGCORE call, governance disabled, skipping");
                return invocation.proceed();
            }
            callerLingId = HOST_Ling_ID;
            log.debug("[Governance Interceptor] No ling context, using LINGCORE as caller: {}", callerLingId);
        } else {
            log.debug("[Governance Interceptor] ling {} calling LINGCORE method: {}.{}",
                    callerLingId, method.getDeclaringClass().getSimpleName(), method.getName());
        }

        // 如果配置为不对灵核应用进行权限检查，直接执行
        if (HOST_Ling_ID.equals(callerLingId) && !checkPermissions) {
            log.debug("[Governance Interceptor] LINGCORE app, permission check disabled, proceeding");
            return invocation.proceed();
        }

        log.info("[Governance Interceptor] Applying governance to method: {}.{} from ling: {}",
                method.getDeclaringClass().getSimpleName(), method.getName(), callerLingId);

        // 构建治理上下文
        InvocationContext ctx = buildInvocationContext(method, args, callerLingId);

        // 通过 GovernanceKernel 执行治理
        return governanceKernel.invoke(null, method, ctx, () -> {
            try {
                return invocation.proceed();
            } catch (Throwable t) {
                throw new InvocationException("LINGCORE bean invocation failed", t);
            }
        });
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
        return InvocationContext.builder()
                .traceId(TraceContext.get())
                .lingId(HOST_Ling_ID)
                .callerLingId(callerLingId)
                .resourceType("RPC")
                .resourceId(method.getDeclaringClass().getSimpleName() + "." + method.getName())
                .operation(method.getName())
                .requiredPermission(permission)
                .accessType(accessType)
                .auditAction(auditAction)
                .shouldAudit(shouldAudit)
                .args(args)
                .metadata(new HashMap<>())
                .labels(new HashMap<>())
                .build();
    }

    /**
     * 判断是否为 Object 的基础方法
     */
    private boolean isObjectMethod(String name) {
        return "toString".equals(name) || "hashCode".equals(name) ||
                "equals".equals(name) || "getClass".equals(name);
    }
}