package com.lingframe.core.pipeline;

import com.lingframe.api.exception.LingInvocationException;
import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.spi.GovernanceDecision;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 治理决策过滤器。
 * 负责把规则解析结果写入治理分区。
 * <p>
 * ⚠️ 它故意放在 resolution 之后、permission 之前：
 * 先拿到目标方法真实视角，再产出 requiredPermission / timeout / auditAction，
 * 后续权限过滤器和线程隔离阶段只消费这里的结果，不再各自重复猜测。
 */
@Slf4j
public class GovernanceDecisionFilter implements LingInvocationFilter {

    private final LingRepository lingRepository;
    private final GovernanceArbitrator governanceArbitrator;

    public GovernanceDecisionFilter(LingRepository lingRepository, GovernanceArbitrator governanceArbitrator) {
        this.lingRepository = lingRepository;
        this.governanceArbitrator = governanceArbitrator;
    }

    @Override
    public int getOrder() {
        return FilterPhase.GOVERNANCE;
    }

    @Override
    public Object doFilter(InvocationContext ctx, LingFilterChain chain) throws Throwable {
        if (governanceArbitrator == null || ctx == null) {
            return chain.doFilter(ctx);
        }

        boolean hasEntryGovernanceFacts = hasEntryGovernanceFacts(ctx);
        Method method = resolveTargetMethod(ctx);
        if (method == null) {
            if (!hasEntryGovernanceFacts) {
                throw new LingInvocationException(ctx.getServiceFQSID(),
                        LingInvocationException.ErrorKind.INTERNAL_ERROR,
                        "Governance target method is unresolved and no entry governance facts were provided");
            }
            return chain.doFilter(ctx);
        }

        LingRuntime runtime = resolveRuntime(ctx);
        GovernanceDecision decision = governanceArbitrator.arbitrate(runtime, method, ctx);
        if (decision == null) {
            if (!hasEntryGovernanceFacts) {
                throw new LingInvocationException(ctx.getServiceFQSID(),
                        LingInvocationException.ErrorKind.INTERNAL_ERROR,
                        "Governance arbitrator returned no decision and no entry governance facts were provided");
            }
            return chain.doFilter(ctx);
        }

        applyDecision(ctx, decision);
        return chain.doFilter(ctx);
    }

    private boolean hasEntryGovernanceFacts(InvocationContext ctx) {
        return ctx != null
                && ctx.governance().getRequiredPermission() != null
                && ctx.governance().getAccessType() != null;
    }

    private LingRuntime resolveRuntime(InvocationContext ctx) {
        LingRuntime runtime = ctx.getLingRuntime();
        if (runtime != null) {
            return runtime;
        }
        if (lingRepository == null) {
            return null;
        }
        String lingId = ctx.getTargetLingId();
        if (lingId == null && ctx.getServiceFQSID() != null) {
            int separator = ctx.getServiceFQSID().indexOf(':');
            if (separator > 0) {
                lingId = ctx.getServiceFQSID().substring(0, separator);
            }
        }
        return lingId == null ? null : lingRepository.getRuntime(lingId);
    }

    private Method resolveTargetMethod(InvocationContext ctx) {
        InvocationResolutionState resolutionState = ctx.resolution();
        if (resolutionState.getResolvedMethod() != null) {
            return resolutionState.getResolvedMethod();
        }

        String methodName = ctx.getMethodName();
        String className = resolutionState.getTargetClassName();
        ClassLoader classLoader = resolutionState.getTargetClassLoader();
        if (methodName == null || methodName.isEmpty() || className == null || className.isEmpty()) {
            return null;
        }
        if (classLoader == null) {
            classLoader = Thread.currentThread().getContextClassLoader();
        }
        if (classLoader == null) {
            classLoader = getClass().getClassLoader();
        }

        try {
            // 这里必须使用解析阶段选定的 ClassLoader 视角，否则灵核与灵元同名类会被解析成两个世界
            Class<?> targetClass = Class.forName(className, false, classLoader);
            Class<?>[] resolvedTypes = resolutionState.getResolvedParameterTypes();
            if (resolvedTypes == null) {
                resolvedTypes = InvocationTypeResolver.resolveTypes(ctx.getParameterTypeNames(), classLoader);
                resolutionState.setResolvedParameterTypes(resolvedTypes);
            }
            Method method = targetClass.getMethod(methodName, resolvedTypes);
            resolutionState.setResolvedMethod(method);
            return method;
        } catch (Exception e) {
            // 治理决策拿不到方法时选择“跳过”，而不是用猜测值污染治理结果
            log.debug("Governance decision skipped because method resolution failed: {}.{}", className, methodName);
            return null;
        }
    }

    private void applyDecision(InvocationContext ctx, GovernanceDecision decision) {
         // ⚠️ 统一写入治理分区，避免再次出现“事实字段”和“治理意图字段”混写在根对象上的问题
        InvocationGovernanceState governanceState = ctx.governance();
        if (decision.getRequiredPermission() != null) {
            governanceState.setRequiredPermission(decision.getRequiredPermission());
        }
        if (decision.getAccessType() != null) {
            governanceState.setAccessType(decision.getAccessType());
        }
        if (decision.getAuditEnabled() != null) {
            governanceState.setShouldAudit(decision.getAuditEnabled());
        }
        if (decision.getAuditAction() != null) {
            governanceState.setAuditAction(decision.getAuditAction());
        }
        if (decision.getSource() != null) {
            governanceState.setRuleSource(decision.getSource());
        }

        Duration timeout = decision.getTimeout();
        if (timeout != null && timeout.toMillis() >= 0) {
            // timeout 在这里收敛成毫秒值，后续线程隔离阶段只消费最终决策，形成闭环
            long timeoutMs = timeout.toMillis();
            governanceState.setTimeoutMs(timeoutMs > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) timeoutMs);
        }
        if (decision.getRateLimitPerSecond() != null) {
            governanceState.setRateLimitPerSecond(decision.getRateLimitPerSecond());
        }
        if (decision.getMaxConcurrentThreads() != null) {
            governanceState.setMaxConcurrentThreads(decision.getMaxConcurrentThreads());
        }
        if (decision.getRetryCount() != null) {
            governanceState.setRetryCount(decision.getRetryCount());
        }
        if (decision.getFallbackValue() != null) {
            governanceState.setFallbackValue(decision.getFallbackValue());
        }
        if (decision.getCpuBudgetMsPerMinute() != null) {
            governanceState.setCpuBudgetMsPerMinute(decision.getCpuBudgetMsPerMinute());
        }
        if (decision.getMemoryBudgetMb() != null) {
            governanceState.setMemoryBudgetMb(decision.getMemoryBudgetMb());
        }
    }
}
