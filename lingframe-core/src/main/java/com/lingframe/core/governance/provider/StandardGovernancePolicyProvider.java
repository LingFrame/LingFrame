package com.lingframe.core.governance.provider;

import com.lingframe.api.annotation.Auditable;
import com.lingframe.api.annotation.RequiresPermission;
import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.governance.GovernanceDecision;
import com.lingframe.core.governance.HostGovernanceRule;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.plugin.PluginInstance;
import com.lingframe.core.plugin.PluginSlot;
import com.lingframe.core.spi.GovernancePolicyProvider;
import com.lingframe.core.strategy.GovernanceStrategy;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 标准治理策略提供者
 * 优先级：P0(Host) > P1(Patch) > P2(Plugin) > P3(Annotation) > P4(Infer)
 */
@Slf4j
public class StandardGovernancePolicyProvider implements GovernancePolicyProvider {

    private final LocalGovernanceRegistry localRegistry;
    // 预编译的宿主规则 (提升匹配性能)
    private final List<CompiledRule> hostRules;

    private record CompiledRule(Pattern pattern, HostGovernanceRule rule) {}

    public StandardGovernancePolicyProvider(LocalGovernanceRegistry localRegistry,
                                            List<HostGovernanceRule> rawRules) {
        this.localRegistry = localRegistry;
        this.hostRules = rawRules.stream()
                .map(r -> new CompiledRule(compilePattern(r.getPattern()), r))
                .toList();
    }

    @Override
    public int getOrder() {
        return 100; // 标准优先级
    }

    @Override
    public GovernanceDecision resolve(PluginSlot slot, Method method, InvocationContext ctx) {
        String pid = (slot != null) ? slot.getPluginId() : "unknown";
        String mName = method.getName();
        // 全限定名匹配键: pluginId.methodName (可扩展为包含类名)
        String fullSign = pid + "." + mName;

        // === P0: 宿主 YAML 强制规则 (最高优先级) ===
        for (CompiledRule cr : hostRules) {
            if (cr.pattern.matcher(fullSign).matches()) {
                HostGovernanceRule r = cr.rule;
                return GovernanceDecision.builder()
                        .requiredPermission(r.getPermission())
                        .accessType(r.getAccessType())
                        .auditEnabled(r.getAuditEnabled())
                        .auditAction(r.getAuditAction())
                        .timeout(r.getTimeout())
                        .build();
            }
        }

        // === P1: 动态补丁 (HotFix) ===
        if (localRegistry != null) {
            GovernancePolicy patch = localRegistry.getPatch(pid);
            GovernanceDecision d1 = matchPolicy(patch, mName);
            if (d1 != null) return d1;
        }

        // === P2: 插件定义 (plugin.yml) ===
        if (slot != null) {
            PluginInstance instance = slot.selectInstance(ctx);
            if (instance != null && instance.getDefinition() != null) {
                GovernanceDecision d2 = matchPolicy(instance.getDefinition().getGovernance(), mName);
                if (d2 != null) return d2;
            }
        }

        // === P3 & P4: 代码级 (注解 & 推导) ===
        GovernanceDecision.GovernanceDecisionBuilder builder = GovernanceDecision.builder();

        // 3.1 权限注解
        RequiresPermission permAnn = method.getAnnotation(RequiresPermission.class);
        if (permAnn != null) {
            builder.requiredPermission(permAnn.value());
            // 如果注解有 access 属性可在此处读取，目前使用默认或推导
        } else {
            // 3.2 智能推导权限 (仅作为默认值)
            builder.requiredPermission(GovernanceStrategy.inferPermission(method));
        }

        // 3.3 审计注解
        if (method.isAnnotationPresent(Auditable.class)) {
            builder.auditEnabled(true);
            Auditable auditAnn = method.getAnnotation(Auditable.class);
            builder.auditAction(auditAnn.action());
        }

        // 3.4 AccessType 推导 (兜底)
        builder.accessType(GovernanceStrategy.inferAccessType(mName));

        return builder.build();
    }

    // --- 辅助方法 ---

    private GovernanceDecision matchPolicy(GovernancePolicy policy, String methodName) {
        if (policy == null) return null;

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
        if (pattern == null) return false;
        if (pattern.equals(methodName)) return true;
        return pattern.endsWith("*") && methodName.startsWith(pattern.substring(0, pattern.length() - 1));
    }
}