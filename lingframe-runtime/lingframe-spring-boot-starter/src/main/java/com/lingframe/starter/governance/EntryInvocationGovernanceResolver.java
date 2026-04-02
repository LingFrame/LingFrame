package com.lingframe.starter.governance;

import com.lingframe.api.config.GovernancePolicy;
import com.lingframe.core.governance.LocalGovernanceRegistry;
import com.lingframe.core.ling.LingInstance;
import com.lingframe.core.ling.LingRepository;
import com.lingframe.core.ling.LingRuntime;
import com.lingframe.core.pipeline.InvocationContext;

public class EntryInvocationGovernanceResolver {

    private final LingRepository lingRepository;
    private final LocalGovernanceRegistry governanceRegistry;

    public EntryInvocationGovernanceResolver(LingRepository lingRepository,
            LocalGovernanceRegistry governanceRegistry) {
        this.lingRepository = lingRepository;
        this.governanceRegistry = governanceRegistry;
    }

    public void applyTo(InvocationContext context, String lingId) {
        if (context == null || lingId == null || lingId.isEmpty()) {
            return;
        }

        GovernancePolicy effectivePolicy = resolveEffectivePolicy(lingId);
        if (effectivePolicy == null || effectivePolicy.getInvocation() == null) {
            return;
        }

        GovernancePolicy.InvocationPolicy invocation = effectivePolicy.getInvocation();
        if (invocation.getTimeoutMs() != null) {
            context.setTimeout(invocation.getTimeoutMs());
        }
        if (invocation.getRateLimitPerSecond() != null) {
            context.setRateLimitPerSecond(invocation.getRateLimitPerSecond());
        }
        if (invocation.getMaxConcurrentThreads() != null) {
            context.setMaxConcurrentThreads(invocation.getMaxConcurrentThreads());
        }
    }

    private GovernancePolicy resolveEffectivePolicy(String lingId) {
        GovernancePolicy base = resolveStaticPolicy(lingId);
        GovernancePolicy patch = governanceRegistry == null ? null : governanceRegistry.getPatch(lingId);
        if (base == null && patch == null) {
            return null;
        }
        return GovernancePolicy.merge(base, patch);
    }

    private GovernancePolicy resolveStaticPolicy(String lingId) {
        if (lingRepository == null) {
            return null;
        }
        LingRuntime runtime = lingRepository.getRuntime(lingId);
        if (runtime == null || runtime.getInstancePool() == null) {
            return null;
        }
        LingInstance instance = runtime.getInstancePool().getDefault();
        if (instance == null || instance.getDefinition() == null || instance.getDefinition().getGovernance() == null) {
            return null;
        }
        return instance.getDefinition().getGovernance().copy();
    }
}
