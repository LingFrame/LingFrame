package com.lingframe.core.pipeline;

import com.lingframe.core.governance.GovernanceArbitrator;
import com.lingframe.core.governance.GovernanceDecision;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.LingFilterChain;
import com.lingframe.core.spi.LingInvocationFilter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 治理决策过滤器
 * <p>
 * 在调用链路中应用治理决策（权限、审计、超时等），并写入 InvocationContext。
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

        Method method = resolveTargetMethod(ctx);
        if (method == null) {
            return chain.doFilter(ctx);
        }

        LingRuntime runtime = resolveRuntime(ctx);
        GovernanceDecision decision = governanceArbitrator.arbitrate(runtime, method, ctx);
        applyDecision(ctx, decision);

        return chain.doFilter(ctx);
    }

    private LingRuntime resolveRuntime(InvocationContext ctx) {
        LingRuntime runtime = ctx.getRuntime();
        if (runtime != null) {
            return runtime;
        }
        if (lingRepository == null) {
            return null;
        }
        String lingId = ctx.getTargetLingId();
        if (lingId == null && ctx.getServiceFQSID() != null) {
            String fqsid = ctx.getServiceFQSID();
            int idx = fqsid.indexOf(':');
            if (idx > 0) {
                lingId = fqsid.substring(0, idx);
            }
        }
        return lingId != null ? lingRepository.getRuntime(lingId) : null;
    }

    private Method resolveTargetMethod(InvocationContext ctx) {
        String methodName = ctx.getMethodName();
        if (methodName == null || methodName.isEmpty()) {
            return null;
        }

        String className = (String) ctx.getAttachments().get("ling.target.className");
        if (className == null && ctx.getServiceFQSID() != null) {
            String serviceName = ctx.getServiceFQSID().split(":", 2)[1];
            if (serviceName.contains("#")) {
                serviceName = serviceName.split("#")[0];
            }
            className = serviceName;
        }

        if (className == null || className.isEmpty()) {
            return null;
        }

        ClassLoader cl = resolveClassLoader(ctx);
        try {
            Class<?> clazz = Class.forName(className, false, cl);
            Class<?>[] resolvedTypes = (Class<?>[]) ctx.getAttachments().get("ling.resolved.types");
            if (resolvedTypes == null) {
                resolvedTypes = resolveTypes(ctx.getParameterTypeNames(), cl);
            }
            return clazz.getMethod(methodName, resolvedTypes);
        } catch (Exception e) {
            log.debug("Governance decision skipped, method not resolved: {}.{}", className, methodName);
            return null;
        }
    }

    private ClassLoader resolveClassLoader(InvocationContext ctx) {
        try {
            LingInstance target = (LingInstance) ctx.getAttachments().get("ling.target.instance");
            if (target != null && target.getContainer() != null) {
                return target.getContainer().getClassLoader();
            }
        } catch (Exception ignored) {
            // fallback to context loader
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : getClass().getClassLoader();
    }

    private Class<?>[] resolveTypes(String[] typeNames, ClassLoader cl) throws ClassNotFoundException {
        if (typeNames == null || typeNames.length == 0) {
            return new Class<?>[0];
        }
        Class<?>[] types = new Class<?>[typeNames.length];
        for (int i = 0; i < typeNames.length; i++) {
            types[i] = loadClass(typeNames[i], cl);
        }
        return types;
    }

    private Class<?> loadClass(String typeName, ClassLoader cl) throws ClassNotFoundException {
        switch (typeName) {
            case "int":
                return int.class;
            case "long":
                return long.class;
            case "double":
                return double.class;
            case "boolean":
                return boolean.class;
            case "byte":
                return byte.class;
            case "short":
                return short.class;
            case "float":
                return float.class;
            case "char":
                return char.class;
            default:
                return Class.forName(typeName, false, cl);
        }
    }

    private void applyDecision(InvocationContext ctx, GovernanceDecision decision) {
        if (decision == null) {
            return;
        }
        if (decision.getRequiredPermission() != null) {
            ctx.setRequiredPermission(decision.getRequiredPermission());
        }
        if (decision.getAccessType() != null) {
            ctx.setAccessType(decision.getAccessType());
        }
        if (decision.getAuditEnabled() != null) {
            ctx.setShouldAudit(decision.getAuditEnabled());
        }
        if (decision.getAuditAction() != null) {
            ctx.setAuditAction(decision.getAuditAction());
        }
        if (decision.getSource() != null) {
            ctx.setRuleSource(decision.getSource());
        }
        Duration timeout = decision.getTimeout();
        if (timeout != null && timeout.toMillis() >= 0) {
            long ms = timeout.toMillis();
            ctx.setTimeout(ms > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ms);
        }
    }
}
