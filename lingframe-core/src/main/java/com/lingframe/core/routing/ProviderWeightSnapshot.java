package com.lingframe.core.routing;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 单个契约的不可变运行期权重覆盖快照。
 * <p>
 * 未列出的提供方仍使用注册权重；本对象不表示接流许可或完整有效路由策略。
 * 修订标识只用于相等比较，不可解析、排序或跨路由器实例复用。
 */
public final class ProviderWeightSnapshot {

    private final String contractId;
    private final String revision;
    private final Map<String, Integer> weights;

    /** 创建独立副本，防止调用者在发布后修改覆盖值。 */
    ProviderWeightSnapshot(String contractId, String revision, Map<String, Integer> weights) {
        this.contractId = requireKey(contractId, "contractId");
        this.revision = requireKey(revision, "revision");
        Objects.requireNonNull(weights, "weights");
        Map<String, Integer> copy = new HashMap<>();
        weights.forEach((key, weight) -> {
            requireKey(key, "providerKey");
            if (weight == null || weight < 0 || weight > 100) {
                throw new IllegalArgumentException("Weight must be between 0 and 100");
            }
            copy.put(key, weight);
        });
        this.weights = Collections.unmodifiableMap(copy);
    }

    /** @return 契约标识 */
    public String getContractId() {
        return contractId;
    }

    /** @return 与所属路由器及契约绑定的不透明修订标识 */
    public String getRevision() {
        return revision;
    }

    /** @return 不可修改的完整覆盖表 */
    public Map<String, Integer> getWeights() {
        return weights;
    }

    /** 校验标识但不自动改写，避免把不同业务键静默合并。 */
    static String requireKey(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
