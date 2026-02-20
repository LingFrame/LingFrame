package com.lingframe.core.governance.provider;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.governance.GovernanceDecision;
import com.lingframe.core.governance.LingCoreGovernanceRule;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.GovernancePolicyProvider;
import com.lingframe.core.strategy.GovernanceStrategy;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
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
    private final List<CompiledRule> hostRules;

    @Value
    public static class CompiledRule {
        Pattern pattern;
        LingCoreGovernanceRule rule;
    }

    public StandardGovernancePolicyProvider(LocalGovernanceRegistry localRegistry,
            List<LingCoreGovernanceRule> rawRules) {
        this.localRegistry = localRegistry;
        this.hostRules = rawRules.stream()
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

        // === P0: 灵核 YAML 强制规则 (最高优先级) ===
        for (CompiledRule cr : hostRules) {
            if (cr.pattern.matcher(fullSign).matches()) {
                LingCoreGovernanceRule r = cr.rule;
                return GovernanceDecision.builder()
                        .requiredPermission(r.getPermission())
                        .accessType(r.getAccessType())
                        .auditEnabled(r.getAuditEnabled())
                        .auditAction(r.getAuditAction())
                        .timeout(r.getTimeout())
                        .source("LINGCORE Rule")
                        .build();
            }
        }

        // === P1: 动态补丁 (HotFix) ===
        if (localRegistry != null) {
            GovernancePolicy patch = localRegistry.getPatch(pid);
            GovernanceDecision d1 = matchPolicy(patch, mName, "Patch");
            if (d1 != null)
                return d1;
        }

        // === P2: 单元定义 (ling.yml) ===
        if (runtime != null) {
            LingInstance instance = runtime.getInstancePool().getDefault();
            if (instance != null && instance.getDefinition() != null) {
                GovernanceDecision d2 = matchPolicy(instance.getDefinition().getGovernance(), mName,
                        "Ling Definition");
                if (d2 != null)
                    return d2;
            }
        }

        // === P3 & P4: 代码级 (注解 & 推导) ===
        GovernanceDecision.GovernanceDecisionBuilder builder = GovernanceDecision.builder();

        // 权限注解
        RequiresPermission permAnn = method.getAnnotation(RequiresPermission.class);
        if (permAnn != null) {
            builder.requiredPermission(permAnn.value());
            builder.source("Annotation");
            // 如果注解有 access 属性可在此处读取，目前使用默认或推导
        } else {
            // 智能推导权限 (仅作为默认值)
            builder.requiredPermission(GovernanceStrategy.inferPermission(method));
            builder.source("Inference");
        }

        // 审计注解
        if (method.isAnnotationPresent(Auditable.class)) {
            builder.auditEnabled(true);
            Auditable auditAnn = method.getAnnotation(Auditable.class);
            builder.auditAction(auditAnn.action());
        }

        // AccessType 推导 (兜底)
        builder.accessType(GovernanceStrategy.inferAccessType(mName));

        return builder.build();
    }

    // --- 辅助方法 ---

    private GovernanceDecision matchPolicy(GovernancePolicy policy, String methodName, String sourceName) {
        if (policy == null)
            return null;

        String perm = null;
        if (policy.getPermissions() != null) {
            for (GovernancePolicy.PermissionRule rule : policy.getPermissions()) {
                if (isMatch(rule.getMethodPattern(), methodName)) {
                    perm = rule.getPermissionId();
                    break;
                }
            }
        }

        Boolean audit = null;
        String action = null;
        if (policy.getAudits() != null) {
            for (GovernancePolicy.AuditRule rule : policy.getAudits()) {
                if (isMatch(rule.getMethodPattern(), methodName)) {
                    audit = rule.isEnabled();
                    action = rule.getAction();
                    break;
                }
            }
        }

        if (perm != null || audit != null) {
            return GovernanceDecision.builder()
                    .requiredPermission(perm)
                    .auditEnabled(audit)
                    .auditAction(action)
                    .source(sourceName)
                    .build();
        }
        return null;
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
}