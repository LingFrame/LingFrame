package com.lingframe.core.routing;

import java.util.Map;
import java.util.Objects;

/**
 * 限定灵元内的不可变版本分流策略。
 * <p>
 * 已配置策略的版本表是完整集合，遗漏版本不参与选路；零权重仅排除普通比例分流，
 * 不阻止显式版本、实例及标签定向。策略不表示最终请求准入或实例隔离。
 */
public final class LingVersionPolicy {
    private final LingRoutingScope scope;
    private final String revision;
    private final boolean configured;
    private final Map<String, Integer> versionWeights;

    /** 创建独立的不可变版本权重副本。 */
    LingVersionPolicy(LingRoutingScope scope, String revision, boolean configured, Map<String, Integer> weights) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.revision = ProviderWeightSnapshot.requireKey(revision, "revision");
        this.configured = configured;
        this.versionWeights = new ProviderWeightSnapshot(scope.getContractId(), revision, weights).getWeights();
        if (!configured && !versionWeights.isEmpty()) {
            throw new IllegalArgumentException("An unconfigured policy must have no weights");
        }
    }

    /** @return 精确作用域 */
    public LingRoutingScope getScope() {
        return scope;
    }

    /** @return 只可相等比较的修订标识 */
    public String getRevision() {
        return revision;
    }

    /** @return 是否显式启用了局部策略；未配置时沿用旧实例路由 */
    public boolean isConfigured() {
        return configured;
    }

    /** @return 不可变的完整版本权重表；已配置的空表不允许任何版本选路 */
    public Map<String, Integer> getVersionWeights() {
        return versionWeights;
    }
}
