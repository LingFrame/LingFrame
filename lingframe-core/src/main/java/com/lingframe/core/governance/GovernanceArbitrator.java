package com.lingframe.core.governance;

import com.lingframe.api.security.AccessType;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.spi.GovernancePolicyProvider;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 治理仲裁器 (责任链核心)
 * 职责：遍历所有 Provider，找到第一个处理结果
 */
@Slf4j
public class GovernanceArbitrator {

    private final List<GovernancePolicyProvider> providers;

    public GovernanceArbitrator(List<GovernancePolicyProvider> providers) {
        // 构造时进行排序：Order 小的在前
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(GovernancePolicyProvider::getOrder))
                .collect(Collectors.toList());
        log.info("GovernanceArbitrator initialized with {} providers", providers.size());
    }

    public GovernanceDecision arbitrate(LingRuntime runtime, Method method, InvocationContext ctx) {
        for (GovernancePolicyProvider provider : providers) {
            GovernanceDecision decision = provider.resolve(runtime, method, ctx);

            // 只要决策中包含核心控制信息 (权限或审计)，即视为有效决策 (First Win 策略)
            if (decision != null && (decision.getRequiredPermission() != null || decision.getAuditEnabled() != null)) {
                return decision;
            }
        }
        // 绝对兜底：防止 NPE，默认放行但需基础权限
        return GovernanceDecision.builder()
                .requiredPermission("default:execute")
                .accessType(AccessType.EXECUTE)
                .auditEnabled(false)
                .build();
    }

}
