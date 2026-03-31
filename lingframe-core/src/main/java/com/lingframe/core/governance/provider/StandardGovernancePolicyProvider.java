package com.lingframe.core.governance.provider;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.governance.GovernanceDecision;
import com.lingframe.core.governance.LingCoreGovernanceRule;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.GovernancePolicyProvider;
import com.lingframe.core.strategy.GovernanceStrategy;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 标准治理策略提供者
 * 优先级：P0(LINGCORE) > P1(Patch) > P2(ling) > P3(Annotation) > P4(Infer)
 */
@Slf4j
public class StandardGovernancePolicyProvider implements GovernancePolicyProvider {

    private final LocalGovernanceRegistry localRegistry;
    // 预编译的灵核规则 (提升匹配性能)
    private final List<CompiledRule> lingCoreRules;

    @Value
    public static class CompiledRule {
        Pattern pattern;
        LingCoreGovernanceRule rule;
    }

    public StandardGovernancePolicyProvider(LocalGovernanceRegistry localRegistry,
            List<LingCoreGovernanceRule> rawRules) {
        this.localRegistry = localRegistry;
        this.lingCoreRules = rawRules.stream()
                .map(r -> new CompiledRule(compilePattern(r.getPattern()), r))
                .collect(Collectors.toList());
    }

    @Override
    public int getOrder() {
        return 100; // 标准优先级
    }

    @Override
    public GovernanceDecision resolve(LingRuntime runtime, Method method, InvocationContext ctx) {
        String pid = (runtime != null) ? runtime.getLingId() : "unknown";
        String mName = method.getName();
        // 全限定名匹配键: lingId.methodName (可扩展为包含类名)
        String fullSign = pid + "." + mName;

        GovernanceDecision.GovernanceDecisionBuilder builder = GovernanceDecision.builder();
        List<String> sources = new ArrayList<>();

        // === P4: 智能推导 (兜底基础值) ===
        builder.requiredPermission(GovernanceStrategy.inferPermission(method));
        builder.accessType(GovernanceStrategy.inferAccessType(mName));
        sources.add("Inference");

        // === P3: 代码级注解 ===
        boolean hasAnnotationOverride = applyAnnotationOverlay(builder, method);
        if (hasAnnotationOverride) {
            replacePrimarySource(sources, "Annotation");
        }

        // === P2: 灵元定义 (ling.yml) ===
        GovernancePolicy lingPolicy = null;
        if (runtime != null) {
            LingInstance instance = runtime.getInstancePool().getDefault();
            if (instance != null && instance.getDefinition() != null) {
                lingPolicy = instance.getDefinition().getGovernance();
                PolicyOverlayResult definitionOverlay = applyPolicyOverlay(builder, lingPolicy, mName);
                if (definitionOverlay.isAccessControlOverride()) {
                    replacePrimarySource(sources, "Ling Definition");
                }
            }
        }

        // === P1: 动态补丁 (HotFix) ===
        if (localRegistry != null) {
            GovernancePolicy patch = localRegistry.getPatch(pid);
            PolicyOverlayResult patchOverlay = applyPolicyOverlay(builder, patch, mName);
            if (patchOverlay.isAccessControlOverride()) {
                replacePrimarySource(sources, "Patch");
            }
        }

        // === P0: 灵核 YAML 强制规则 (最高优先级) ===
        for (CompiledRule cr : lingCoreRules) {
            if (cr.pattern.matcher(fullSign).matches()) {
                LingCoreGovernanceRule r = cr.rule;
                applyCoreRuleOverlay(builder, r);
                replacePrimarySource(sources, "LINGCORE Rule");
                break;
            }
        }

        builder.source(String.join(" <- ", sources));
        GovernanceDecision decision = builder.build();
        return decision.hasAnyDirective() ? decision : null;
    }

    // --- 辅助方法 ---

    private boolean applyAnnotationOverlay(GovernanceDecision.GovernanceDecisionBuilder builder, Method method) {
        boolean overridden = false;

        RequiresPermission permAnn = method.getAnnotation(RequiresPermission.class);
        if (permAnn != null) {
            builder.requiredPermission(permAnn.value());
            overridden = true;
        }

        if (method.isAnnotationPresent(Auditable.class)) {
            Auditable auditAnn = method.getAnnotation(Auditable.class);
            builder.auditEnabled(true);
            builder.auditAction(auditAnn.action());
            overridden = true;
        }

        return overridden;
    }

    private PolicyOverlayResult applyPolicyOverlay(GovernanceDecision.GovernanceDecisionBuilder builder,
                                                   GovernancePolicy policy,
                                                   String methodName) {
        if (policy == null) {
            return PolicyOverlayResult.none();
        }

        boolean accessControlOverride = false;
        boolean invocationOverride = false;

        if (policy.getPermissions() != null) {
            for (GovernancePolicy.PermissionRule rule : policy.getPermissions()) {
                if (isMatch(rule.getMethodPattern(), methodName)) {
                    builder.requiredPermission(rule.getPermissionId());
                    accessControlOverride = true;
                    break;
                }
            }
        }

        if (policy.getAudits() != null) {
            for (GovernancePolicy.AuditRule rule : policy.getAudits()) {
                if (isMatch(rule.getMethodPattern(), methodName)) {
                    builder.auditEnabled(rule.isEnabled());
                    builder.auditAction(rule.getAction());
                    accessControlOverride = true;
                    break;
                }
            }
        }

        GovernancePolicy.InvocationPolicy invocation = policy.getInvocation();
        if (invocation != null) {
            if (invocation.getTimeoutMs() != null) {
                builder.timeout(Duration.ofMillis(invocation.getTimeoutMs()));
                invocationOverride = true;
            }
            if (invocation.getRateLimitPerSecond() != null) {
                builder.rateLimitPerSecond(invocation.getRateLimitPerSecond());
                invocationOverride = true;
            }
            if (invocation.getMaxConcurrentThreads() != null) {
                builder.maxConcurrentThreads(invocation.getMaxConcurrentThreads());
                invocationOverride = true;
            }
        }

        return new PolicyOverlayResult(accessControlOverride, invocationOverride);
    }

    private void applyCoreRuleOverlay(GovernanceDecision.GovernanceDecisionBuilder builder, LingCoreGovernanceRule rule) {
        if (rule.getPermission() != null) {
            builder.requiredPermission(rule.getPermission());
        }
        if (rule.getAccessType() != null) {
            builder.accessType(rule.getAccessType());
        }
        if (rule.getAuditEnabled() != null) {
            builder.auditEnabled(rule.getAuditEnabled());
        }
        if (rule.getAuditAction() != null) {
            builder.auditAction(rule.getAuditAction());
        }
        if (rule.getTimeout() != null) {
            builder.timeout(rule.getTimeout());
        }
        if (rule.getRetryCount() != null) {
            builder.retryCount(rule.getRetryCount());
        }
        if (rule.getFallbackValue() != null) {
            builder.fallbackValue(rule.getFallbackValue());
        }
    }

    private void replacePrimarySource(List<String> sources, String source) {
        sources.remove(source);
        sources.add(0, source);
    }

    private Pattern compilePattern(String antPattern) {
        // 简单将 AntPath 转为 Regex (* -> .*)，生产级建议引入 Spring AntPathMatcher 逻辑
        String regex = "^" + antPattern.replace(".", "\\.").replace("*", ".*") + "$";
        return Pattern.compile(regex);
    }

    private boolean isMatch(String pattern, String methodName) {
        if (pattern == null)
            return false;
        if (pattern.equals(methodName))
            return true;
        return pattern.endsWith("*") && methodName.startsWith(pattern.substring(0, pattern.length() - 1));
    }

    @Value
    private static class PolicyOverlayResult {
        boolean accessControlOverride;
        boolean invocationOverride;

        static PolicyOverlayResult none() {
            return new PolicyOverlayResult(false, false);
        }
    }
}
