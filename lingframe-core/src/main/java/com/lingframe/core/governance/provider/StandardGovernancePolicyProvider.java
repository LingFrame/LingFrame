package com.lingframe.core.governance.provider;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.RequiresPermission;

import java.lang.annotation.Annotation;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.spi.GovernanceDecision;
import com.lingframe.core.governance.LingCoreGovernanceRule;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.pipeline.InvocationContext;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.GovernancePolicyProvider;
import com.lingframe.core.governance.GovernanceStrategy;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
        List<CompiledRule> compiled = new ArrayList<>();
        if (rawRules != null) {
            for (LingCoreGovernanceRule r : rawRules) {
                // 🔥 防御式构造：单条规则的 pattern 问题不应中断整个 Provider 初始化
                // null/empty pattern → warn 后跳过
                // PatternSyntaxException → error 后跳过
                // 这样坏规则只影响自身，不会让所有灵核规则全部失效
                try {
                    Pattern pattern = compilePattern(r.getPattern());
                    if (pattern == null) {
                        // null/empty 已在 compilePattern 内 warn
                        continue;
                    }
                    compiled.add(new CompiledRule(pattern, r));
                } catch (PatternSyntaxException e) {
                    log.error("Invalid governance rule pattern [{}], skipping this rule: {}",
                            r.getPattern(), e.getMessage());
                }
            }
        }
        this.lingCoreRules = compiled;
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
        if (permAnn == null) {
            permAnn = findInterfaceAnnotation(method, RequiresPermission.class);
        }
        if (permAnn != null) {
            builder.requiredPermission(permAnn.value());
            overridden = true;
        }

        Auditable auditAnn = method.getAnnotation(Auditable.class);
        if (auditAnn == null) {
            auditAnn = findInterfaceAnnotation(method, Auditable.class);
        }
        if (auditAnn != null) {
            builder.auditEnabled(true);
            builder.auditAction(auditAnn.action());
            overridden = true;
        }

        // 注解只声明契约：@LingService 不再承载治理入参（timeout 已删）。
        // 超时/降级/重试等治理入参收敛到 YAML references 分区，由 applyPolicyOverlay 处理。

        return overridden;
    }

    private <A extends Annotation> A findInterfaceAnnotation(Method method, Class<A> annotationType) {
        for (Class<?> iface : method.getDeclaringClass().getInterfaces()) {
            try {
                Method ifaceMethod = iface.getMethod(method.getName(), method.getParameterTypes());
                A ann = ifaceMethod.getAnnotation(annotationType);
                if (ann != null) {
                    return ann;
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
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

        // 跨灵元服务引用治理规则：原散在 @LingReference.timeout/fallback 的入参收敛到此。
        // 优先级语义：references（被调方方法名维度）先覆，invocation（被调方策略级）后覆——
        // 后者优先级更高，被调方策略应能覆盖调用方侧声明。两者都命中同一方法且设同字段时，
        // invocation 段会覆盖 references 段的值。
        if (policy.getReferences() != null) {
            for (GovernancePolicy.ReferenceRule rule : policy.getReferences()) {
                if (isMatch(rule.getReferencePattern(), methodName)) {
                    if (rule.getTimeoutMs() != null) {
                        builder.timeout(Duration.ofMillis(rule.getTimeoutMs()));
                        invocationOverride = true;
                    }
                    if (rule.getRetryCount() != null) {
                        builder.retryCount(rule.getRetryCount());
                        invocationOverride = true;
                    }
                    if (rule.getFallbackValue() != null) {
                        builder.fallbackValue(rule.getFallbackValue());
                        invocationOverride = true;
                    }
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
            if (invocation.getRetryCount() != null) {
                builder.retryCount(invocation.getRetryCount());
                invocationOverride = true;
            }
            if (invocation.getFallbackValue() != null) {
                builder.fallbackValue(invocation.getFallbackValue());
                invocationOverride = true;
            }
            if (invocation.getCpuBudgetMsPerMinute() != null) {
                builder.cpuBudgetMsPerMinute(invocation.getCpuBudgetMsPerMinute());
                invocationOverride = true;
            }
            if (invocation.getMemoryBudgetMb() != null) {
                builder.memoryBudgetMb(invocation.getMemoryBudgetMb());
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

    /**
     * 将 Ant 风格模式编译为正则 Pattern。
     * <p>
     * <b>适用范围</b>：仅适用于方法名匹配（如 {@code lingId.methodName}），
     * 其中 {@code *} 会被编译为 {@code .*}，可匹配含 {@code .} 的任意字符，
     * 因此<b>不适用于路径匹配</b>（路径分隔符不应被 {@code *} 跨越）。
     *
     * @param antPattern Ant 风格模式，{@code *} 匹配任意字符（含 .），{@code ?} 匹配单字符
     * @return 编译后的 Pattern；null/empty 模式返回 null
     */
    private Pattern compilePattern(String antPattern) {
        // 🔥 null/empty 防御：原实现直接 antPattern.replace 会抛 NPE，导致整个 Provider 构造失败
        if (antPattern == null || antPattern.isEmpty()) {
            log.warn("Skipping governance rule with null/empty pattern");
            return null;
        }
        // Ant 风格转 Regex：
        // - * → .* （匹配任意字符，含 .）
        // - ? → . （匹配单字符）
        // - 其他正则元字符（. + ( ) [ ] { } | ^ $ \ 等）用 Pattern.quote 转义，
        //   避免被误判为正则语法（原实现只转义了 .，遇到 user.svc+backup 这种会出错）
        StringBuilder regex = new StringBuilder("^");
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < antPattern.length(); i++) {
            char c = antPattern.charAt(i);
            if (c == '*') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(".*");
            } else if (c == '?') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        regex.append("$");
        return Pattern.compile(regex.toString());
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
